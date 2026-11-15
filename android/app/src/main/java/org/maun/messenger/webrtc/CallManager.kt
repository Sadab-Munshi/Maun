package org.maun.messenger.webrtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.SystemClock
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maun.messenger.ChatEngine
import org.maun.messenger.Payload
import org.webrtc.AudioSource
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/** A call as the screen shows it. Null means there is none. */
data class CallUi(
    /** The other person's ID. */
    val peer: String,
    /** They called. */
    val incoming: Boolean,
    val phase: Phase,
    /** When the two phones got through to each other, on the clock that does not jump ([SystemClock.elapsedRealtime]). */
    val connectedAt: Long = 0,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    /** Once it is over: why, in words. */
    val ended: String? = null,
    /** A video call. */
    val video: Boolean = false,
    /** In a video call: this phone's camera is sending. */
    val cameraOn: Boolean = false,
    /** In a video call: the camera in use is the one facing the user. */
    val frontCamera: Boolean = true,
    /** In a video call: pictures from the other phone have started to arrive. */
    val remoteVideo: Boolean = false,
) {
    enum class Phase {
        /** Ringing on their phone (a call going out) or on this one (a call coming in). */
        Ringing,

        /** Answered; the phones are finding their way to each other. */
        Connecting,

        /** Talking. */
        Active,

        /** Over. Shown for a moment, then gone. */
        Ended,
    }
}

/** What a call needs from the rest of the app. */
interface CallSignals {
    /** Sends one step of a call to [peer], encrypted like any message. False when it could not leave. */
    suspend fun send(peer: String, call: Payload.Call, urgent: Boolean): Boolean

    /** The servers this call is carried through. Throws with the reason when there are none. */
    suspend fun servers(): List<ChatEngine.CallServer>

    /** Writes how a call went into the conversation. [missed] makes it count as unread. */
    fun note(peer: String, text: String, missed: Boolean)

    /** Shows, or takes away, the notification of a call that is ringing here. */
    fun ringing(peer: String?, on: Boolean, video: Boolean)
}

/**
 * Stands between a stream of pictures and whatever is showing them at the
 * moment. The stream is told about this once; the screen comes and goes by
 * setting [target], and never has to touch the stream itself, which may be
 * gone by the time the screen lets go.
 */
class PictureSink : VideoSink {
    @Volatile
    var target: VideoSink? = null

    /** Called once, when the first picture comes through. */
    @Volatile
    var onFirst: (() -> Unit)? = null

    override fun onFrame(frame: VideoFrame) {
        onFirst?.let {
            onFirst = null
            it()
        }
        target?.onFrame(frame)
    }
}

/**
 * One call at a time, voice or video, between this phone and a contact.
 *
 * The call is set up with encrypted messages (an offer, an answer, and the
 * ways each phone can be reached) and then runs phone to phone through the
 * relay's call servers and nothing else: with [PeerConnection.IceTransportsType.RELAY]
 * neither phone learns the other's address. Sound and pictures are encrypted
 * between the two phones by WebRTC; the call servers carry what they cannot
 * read.
 *
 * Everything here happens on the main thread.
 */
class CallManager(context: Context, private val signals: CallSignals) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = CallAudio(appContext)

    private val _state = MutableStateFlow<CallUi?>(null)
    val state: StateFlow<CallUi?> = _state.asStateFlow()

    /** What the screen needs to draw video with. Shared by the camera, the coders and the views. */
    private val egl: EglBase by lazy { EglBase.create() }
    val eglContext: EglBase.Context get() = egl.eglBaseContext

    /** The other phone's pictures, and this phone's own. The screen sets their targets. */
    val remotePictures = PictureSink()
    val ownPictures = PictureSink()

    private var callId: String? = null
    private var connection: PeerConnection? = null
    private var microphone: AudioSource? = null
    private var camera: CameraVideoCapturer? = null
    private var cameraSource: VideoSource? = null
    private var cameraHelper: SurfaceTextureHelper? = null
    private var cameraTrack: VideoTrack? = null
    private var offer: String? = null
    private var remoteSet = false
    private val waitingCandidates = ArrayList<IceCandidate>()
    private var setup: Job? = null
    private var ringTimeout: Job? = null
    private var lostTimeout: Job? = null
    private var clearing: Job? = null

    // Made once, the first time a call needs it: loading the library takes a moment.
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions())
        val device = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(device)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()
    }

    /** A call is being set up, ringing or running. */
    val busy: Boolean get() = _state.value.let { it != null && it.phase != CallUi.Phase.Ended }

    // ---- From the screen -------------------------------------------------------

    /** Calls [peer]. The permissions (microphone, and camera for [video]) have been given by the time this is called. */
    fun start(peer: String, video: Boolean = false) {
        if (busy) return
        clearing?.cancel()
        val id = UUID.randomUUID().toString()
        callId = id
        _state.value = CallUi(peer = peer, incoming = false, phase = CallUi.Phase.Ringing, video = video, cameraOn = video, speaker = video)
        setup = scope.launch {
            try {
                val pc = connect(peer, id, video)
                // Only now is there a call to keep alive and to ring for: one
                // that the relay cannot serve ends before anything is started.
                CallService.start(appContext, video)
                audio.begin()
                if (video) audio.setSpeaker(true)
                audio.ringOutgoing()
                val description = pc.createOffer(video)
                pc.setLocal(description)
                if (!signals.send(peer, Payload.Call(id, Payload.CALL_OFFER, sdp = description.description, video = video), urgent = true)) {
                    finish("Could not reach them. Check your connection.", note = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finish(e.message ?: "The call could not be started.", note = null)
            }
        }
        ringTimeout = scope.launch {
            delay(RING_MILLIS)
            hangUp(told = "No answer", note = "No answer")
        }
    }

    /** Takes the call that is ringing here. */
    fun accept() {
        val call = _state.value ?: return
        val id = callId ?: return
        val remote = offer ?: return
        if (!call.incoming || call.phase != CallUi.Phase.Ringing) return
        ringTimeout?.cancel()
        audio.stopRinging()
        signals.ringing(call.peer, false, call.video)
        _state.value = call.copy(phase = CallUi.Phase.Connecting, cameraOn = call.video, speaker = call.video)
        CallService.start(appContext, call.video)
        audio.begin()
        if (call.video) audio.setSpeaker(true)
        setup = scope.launch {
            try {
                val pc = connect(call.peer, id, call.video)
                pc.setRemote(SessionDescription(SessionDescription.Type.OFFER, remote))
                remoteSet = true
                waitingCandidates.forEach { pc.addIceCandidate(it) }
                waitingCandidates.clear()
                val description = pc.createAnswer(call.video)
                pc.setLocal(description)
                if (!signals.send(call.peer, Payload.Call(id, Payload.CALL_ANSWER, sdp = description.description), urgent = false)) {
                    finish("Could not reach them. Check your connection.", note = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hangUp(told = e.message ?: "The call could not be connected.", note = "Call not connected")
            }
        }
    }

    /** Ends the call from this side: refusing one that rings, giving up on one that is going out, or hanging up. */
    fun hangUp() {
        val call = _state.value ?: return
        val kind = if (call.video) "video call" else "voice call"
        when {
            call.phase == CallUi.Phase.Ended -> Unit
            call.incoming && call.phase == CallUi.Phase.Ringing -> hangUp(told = "Declined", note = "Declined $kind", reason = "declined")
            call.connectedAt == 0L && !call.incoming -> hangUp(told = "Call ended", note = "Call not answered")
            else -> hangUp(told = "Call ended", note = null)
        }
    }

    fun setMuted(muted: Boolean) {
        val call = _state.value ?: return
        connection?.senders?.forEach { sender ->
            runCatching { sender.track()?.takeIf { it.kind() == "audio" }?.setEnabled(!muted) }
        }
        _state.value = call.copy(muted = muted)
    }

    fun setSpeaker(on: Boolean) {
        val call = _state.value ?: return
        audio.setSpeaker(on)
        _state.value = call.copy(speaker = on)
    }

    /** In a video call: stops or restarts this phone's camera. The call goes on with sound. */
    fun setCamera(on: Boolean) {
        val call = _state.value ?: return
        if (!call.video || call.cameraOn == on) return
        runCatching { cameraTrack?.setEnabled(on) }
        runCatching { if (on) camera?.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS) else camera?.stopCapture() }
        _state.value = call.copy(cameraOn = on)
    }

    /** In a video call: changes between the camera facing the user and the one on the back. */
    fun switchCamera() {
        val call = _state.value ?: return
        if (!call.video) return
        runCatching {
            camera?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(front: Boolean) {
                    scope.launch { _state.value?.let { _state.value = it.copy(frontCamera = front) } }
                }

                override fun onCameraSwitchError(error: String?) = Unit
            })
        }
    }

    // ---- From the other phone ---------------------------------------------------

    /** A step of a call has arrived from [from], after waiting [ageMillis] on the relay. Any thread. */
    fun onSignal(from: String, call: Payload.Call, ageMillis: Long) {
        scope.launch { handle(from, call, ageMillis) }
    }

    private suspend fun handle(from: String, step: Payload.Call, ageMillis: Long) {
        val current = _state.value?.takeIf { it.phase != CallUi.Phase.Ended }
        if (step.type == Payload.CALL_OFFER) {
            val sdp = step.sdp ?: return
            val kind = if (step.video) "video call" else "voice call"
            when {
                // The phone was off or out of reach while it rang.
                ageMillis > RING_MILLIS -> signals.note(from, "Missed $kind", missed = true)
                current != null -> if (step.callId != callId) signals.send(from, Payload.Call(step.callId, Payload.CALL_BUSY), urgent = false)
                else -> {
                    clearing?.cancel()
                    callId = step.callId
                    offer = sdp
                    remoteSet = false
                    waitingCandidates.clear()
                    _state.value = CallUi(peer = from, incoming = true, phase = CallUi.Phase.Ringing, video = step.video)
                    audio.ringIncoming()
                    signals.ringing(from, true, step.video)
                    ringTimeout = scope.launch {
                        delay(RING_MILLIS - ageMillis)
                        finish("Missed call", note = "Missed $kind", missed = true)
                    }
                }
            }
            return
        }
        if (current == null || step.callId != callId || from != current.peer) return
        val kind = if (current.video) "video call" else "voice call"
        when (step.type) {
            Payload.CALL_ANSWER -> {
                val sdp = step.sdp ?: return
                if (current.incoming || current.phase != CallUi.Phase.Ringing) return
                ringTimeout?.cancel()
                audio.stopRinging()
                _state.value = current.copy(phase = CallUi.Phase.Connecting)
                val pc = connection ?: return
                try {
                    pc.setRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                    remoteSet = true
                    waitingCandidates.forEach { pc.addIceCandidate(it) }
                    waitingCandidates.clear()
                } catch (e: Exception) {
                    hangUp(told = "The call could not be connected.", note = "Call not connected")
                }
            }
            Payload.CALL_ICE -> {
                val candidate = step.candidate ?: return
                val ice = IceCandidate(candidate.mid, candidate.index, candidate.sdp)
                val pc = connection
                if (pc != null && remoteSet) pc.addIceCandidate(ice) else waitingCandidates.add(ice)
            }
            Payload.CALL_HANGUP -> when {
                current.incoming && current.phase == CallUi.Phase.Ringing -> finish("Missed call", note = "Missed $kind", missed = true)
                current.connectedAt == 0L && step.reason == "declined" -> finish("Declined", note = "Call declined")
                else -> finish("Call ended", note = null)
            }
            Payload.CALL_BUSY -> finish("Busy on another call", note = "They were on another call")
        }
    }

    // ---- The connection --------------------------------------------------------

    /** Opens the connection for a call and puts this phone's microphone, and for [video] its camera, on it. */
    private suspend fun connect(peer: String, id: String, video: Boolean): PeerConnection {
        val servers = signals.servers().map {
            PeerConnection.IceServer.builder(it.urls).setUsername(it.username).setPassword(it.credential).createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            // Through the call servers only: the phones never learn each other's address.
            iceTransportsType = PeerConnection.IceTransportsType.RELAY
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        val pc = factory.createPeerConnection(config, Watcher(peer, id)) ?: throw IllegalStateException("The call could not be started.")
        connection = pc
        val source = factory.createAudioSource(MediaConstraints())
        microphone = source
        pc.addTrack(factory.createAudioTrack("voice", source), listOf("maun"))
        if (video) addCamera(pc)
        return pc
    }

    /** Starts the camera facing the user and sends what it sees. A phone without a usable camera sends sound only. */
    private fun addCamera(pc: PeerConnection) {
        try {
            val allowed = ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (!allowed) {
                _state.value?.let { _state.value = it.copy(cameraOn = false) }
                return
            }
            val cameras = Camera2Enumerator(appContext)
            val names = cameras.deviceNames
            val name = names.firstOrNull { cameras.isFrontFacing(it) } ?: names.firstOrNull() ?: return
            val capturer = cameras.createCapturer(name, null) ?: return
            val helper = SurfaceTextureHelper.create("camera", eglContext)
            val source = factory.createVideoSource(false)
            capturer.initialize(helper, appContext, source.capturerObserver)
            capturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
            val track = factory.createVideoTrack("camera", source)
            track.addSink(ownPictures)
            val sender = pc.addTrack(track, listOf("maun"))
            // Modest on purpose: the call goes through a relay, and the other side may be on a slow line.
            runCatching {
                val parameters = sender.parameters
                parameters.encodings.forEach { it.maxBitrateBps = VIDEO_MAX_BITS_PER_SECOND }
                sender.parameters = parameters
            }
            camera = capturer
            cameraHelper = helper
            cameraSource = source
            cameraTrack = track
            _state.value?.let { _state.value = it.copy(cameraOn = true, frontCamera = cameras.isFrontFacing(name)) }
        } catch (_: Exception) {
            _state.value?.let { _state.value = it.copy(cameraOn = false) }
        }
    }

    /** What the connection reports, on a thread of its own; everything is taken over to the main thread. */
    private inner class Watcher(private val peer: String, private val id: String) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            scope.launch {
                if (callId != id) return@launch
                val found = Payload.Candidate(candidate.sdpMid.orEmpty(), candidate.sdpMLineIndex, candidate.sdp)
                signals.send(peer, Payload.Call(id, Payload.CALL_ICE, candidate = found), urgent = false)
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            scope.launch {
                if (callId != id) return@launch
                val call = _state.value ?: return@launch
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                        lostTimeout?.cancel()
                        ringTimeout?.cancel()
                        audio.stopRinging()
                        if (call.phase != CallUi.Phase.Ended) {
                            _state.value = call.copy(
                                phase = CallUi.Phase.Active,
                                connectedAt = if (call.connectedAt == 0L) SystemClock.elapsedRealtime() else call.connectedAt,
                            )
                        }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        // A lift, a tunnel, a change from Wi-Fi to mobile: give it a moment to come back.
                        lostTimeout?.cancel()
                        lostTimeout = scope.launch {
                            delay(LOST_MILLIS)
                            hangUp(told = "The connection was lost", note = null)
                        }
                    }
                    PeerConnection.IceConnectionState.FAILED -> hangUp(told = "The call could not get through", note = null)
                    else -> Unit
                }
            }
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            // The other phone's camera. Its pictures go to whatever view is on screen.
            val track = receiver.track() as? VideoTrack ?: return
            remotePictures.onFirst = {
                scope.launch { if (callId == id) _state.value?.let { _state.value = it.copy(remoteVideo = true) } }
            }
            runCatching { track.addSink(remotePictures) }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    // ---- Ending ----------------------------------------------------------------

    /** Tells the other phone it is over, then ends it here. */
    private fun hangUp(told: String, note: String?, reason: String? = null) {
        val call = _state.value ?: return
        val id = callId
        if (id != null && call.phase != CallUi.Phase.Ended) {
            scope.launch { signals.send(call.peer, Payload.Call(id, Payload.CALL_HANGUP, reason = reason), urgent = false) }
        }
        finish(told, note)
    }

    /**
     * Ends the call on this phone: lets go of the microphone, the camera and
     * the connection, puts the sound back, and writes a line into the
     * conversation. A call that got through is noted with its length whatever
     * [note] says.
     */
    private fun finish(told: String, note: String?, missed: Boolean = false) {
        val call = _state.value ?: return
        if (call.phase == CallUi.Phase.Ended) return
        setup?.cancel()
        ringTimeout?.cancel()
        lostTimeout?.cancel()
        callId = null
        offer = null
        remoteSet = false
        waitingCandidates.clear()
        remotePictures.onFirst = null
        connection?.let { runCatching { it.dispose() } }
        connection = null
        microphone?.let { runCatching { it.dispose() } }
        microphone = null
        camera?.let {
            runCatching { it.stopCapture() }
            runCatching { it.dispose() }
        }
        camera = null
        cameraSource?.let { runCatching { it.dispose() } }
        cameraSource = null
        cameraHelper?.let { runCatching { it.dispose() } }
        cameraHelper = null
        cameraTrack = null
        audio.release()
        signals.ringing(call.peer, false, call.video)
        CallService.stop(appContext)

        val line = if (call.connectedAt != 0L) {
            "${if (call.video) "Video call" else "Voice call"}, ${length(SystemClock.elapsedRealtime() - call.connectedAt)}"
        } else {
            note
        }
        if (line != null) signals.note(call.peer, line, missed)

        _state.value = call.copy(phase = CallUi.Phase.Ended, ended = told, cameraOn = false, remoteVideo = false)
        clearing = scope.launch {
            delay(ENDED_SHOWN_MILLIS)
            if (_state.value?.phase == CallUi.Phase.Ended) _state.value = null
        }
    }

    private fun length(millis: Long): String {
        val seconds = millis / 1000
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    companion object {
        /** How long a call rings before it counts as not answered. */
        const val RING_MILLIS = 45_000L
        private const val LOST_MILLIS = 15_000L
        private const val ENDED_SHOWN_MILLIS = 2_000L

        private const val VIDEO_WIDTH = 640
        private const val VIDEO_HEIGHT = 480
        private const val VIDEO_FPS = 24
        private const val VIDEO_MAX_BITS_PER_SECOND = 800_000
    }
}

// ---- WebRTC's callbacks, as functions that wait -------------------------------

private open class Waiting(private val onDone: (Result<SessionDescription?>) -> Unit) : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = onDone(Result.success(description))
    override fun onSetSuccess() = onDone(Result.success(null))
    override fun onCreateFailure(reason: String?) = onDone(Result.failure(IllegalStateException("The call could not be set up.")))
    override fun onSetFailure(reason: String?) = onDone(Result.failure(IllegalStateException("The call could not be set up.")))
}

private suspend fun described(ask: (SdpObserver) -> Unit): SessionDescription? = suspendCancellableCoroutine { waiting ->
    ask(Waiting { result -> result.fold({ if (waiting.isActive) waiting.resume(it) }, { if (waiting.isActive) waiting.resumeWithException(it) }) })
}

private fun wanted(video: Boolean) = MediaConstraints().apply {
    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
    if (video) mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
}

private suspend fun PeerConnection.createOffer(video: Boolean): SessionDescription =
    described { createOffer(it, wanted(video)) } ?: throw IllegalStateException("The call could not be set up.")

private suspend fun PeerConnection.createAnswer(video: Boolean): SessionDescription =
    described { createAnswer(it, wanted(video)) } ?: throw IllegalStateException("The call could not be set up.")

private suspend fun PeerConnection.setLocal(description: SessionDescription) {
    described { setLocalDescription(it, description) }
}

private suspend fun PeerConnection.setRemote(description: SessionDescription) {
    described { setRemoteDescription(it, description) }
}
