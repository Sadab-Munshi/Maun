package org.maun.messenger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import org.maun.messenger.ui.CallActions
import org.maun.messenger.ui.CallScreen
import org.maun.messenger.ui.CallVideo
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import org.maun.messenger.push.Background
import org.maun.messenger.ui.AppRoot
import org.maun.messenger.ui.LockScreen
import org.maun.messenger.ui.MaunTheme

class MainActivity : ComponentActivity() {

    private val messenger: Messenger
        get() = (application as MaunApp).messenger

    /** Whether the unlock question was already put since the app came on screen. */
    private var offeredUnlock = false

    /** Everything this window draws. */
    private val content: View
        get() = findViewById(android.R.id.content)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppLock.onCreate(this)
        lifecycleScope.launch { messenger.calls.state.collect { showOverLockScreen(it != null) } }
        if (savedInstanceState == null && intent?.action == ACTION_ANSWER) answer()
        setContent {
            MaunTheme {
                // While locked, none of the app is drawn, and no conversation
                // counts as read. Where the user was is kept for afterwards.
                val screens = rememberSaveableStateHolder()
                val call by messenger.calls.state.collectAsStateWithLifecycle()
                val contacts by messenger.contacts.collectAsStateWithLifecycle()
                val current = call
                if (current != null) {
                    // A call comes before everything, the app lock included:
                    // it can be taken without unlocking, and shows nothing else.
                    SideEffect(::reveal)
                    val calls = messenger.calls
                    CallScreen(
                        call = current,
                        name = contacts.firstOrNull { it.id == current.peer }?.name ?: "Unknown",
                        actions = remember {
                            CallActions(
                                onAccept = ::answer,
                                onHangUp = calls::hangUp,
                                onMute = calls::setMuted,
                                onSpeaker = calls::setSpeaker,
                                onCamera = calls::setCamera,
                                onSwitchCamera = calls::switchCamera,
                            )
                        },
                        // What video is drawn with is only made when a call has pictures.
                        video = if (current.video) remember { CallVideo(calls.eglContext, calls.remotePictures, calls.ownPictures) } else null,
                    )
                } else if (AppLock.locked) {
                    LockScreen(onShown = ::reveal, onUnlock = { AppLock.unlock(this) })
                } else {
                    screens.SaveableStateProvider("app") { AppRoot() }
                }
            }
        }
    }

    /**
     * Takes the call that is ringing, once the microphone may be used. A video
     * call asks for the camera too, and goes ahead with sound only without it.
     */
    private fun answer() {
        val video = messenger.calls.state.value?.video == true
        val needed = listOfNotNull(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA.takeIf { video })
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) messenger.calls.accept() else askForCall.launch(needed.toTypedArray())
    }

    private val askForCall = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (mic) {
            messenger.calls.accept()
        } else {
            Toast.makeText(this, "Maun needs the microphone for a call.", Toast.LENGTH_LONG).show()
            messenger.calls.hangUp()
        }
    }

    /** A call may show over the lock screen and wake the display; nothing else may. */
    private fun showOverLockScreen(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
        }
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // "Answer" on the notification of a call that is ringing.
        if (intent.action == ACTION_ANSWER) answer()
    }

    override fun onStart() {
        super.onStart()
        messenger.onForeground()
        Background.resume(this)
    }

    override fun onResume() {
        super.onResume()
        // Ask once by itself. After a refusal the lock screen's button asks again.
        if (AppLock.locked && !offeredUnlock) {
            offeredUnlock = true
            AppLock.unlock(this)
        }
        // In case the lock screen never reports in, the window must not stay blank.
        content.postDelayed(::reveal, REVEAL_AFTER_MILLIS)
    }

    override fun onStop() {
        messenger.onBackground()
        // On some phones the question is a screen of its own, which stops this one.
        if (!AppLock.asking) offeredUnlock = false
        val wasLocked = AppLock.locked
        AppLock.onStop(this)
        // The screens are redrawn only once the app is back in front. Until
        // the lock screen has taken their place, the window shows nothing
        // rather than, for an instant, the conversation that was open.
        if (AppLock.locked && !wasLocked) content.visibility = View.INVISIBLE
        super.onStop()
    }

    private fun reveal() {
        content.visibility = View.VISIBLE
    }

    companion object {
        /** Sent by the notification of a ringing call: open, and take it. */
        const val ACTION_ANSWER = "org.maun.messenger.ANSWER"
        private const val REVEAL_AFTER_MILLIS = 500L
    }
}
