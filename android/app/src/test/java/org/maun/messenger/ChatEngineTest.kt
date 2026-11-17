package org.maun.messenger

import org.maun.messenger.network.RelayException
import org.maun.messenger.network.FileException
import org.maun.messenger.network.FileApi
import org.maun.messenger.crypto.FileCipher
import java.util.Base64
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.maun.messenger.crypto.Identity
import org.maun.messenger.crypto.SignalSessions
import org.maun.messenger.crypto.SignalStorage
import org.maun.messenger.crypto.SoftwareDeviceSigner
import org.maun.messenger.data.MessageEntity
import org.maun.messenger.data.MessageKind
import org.maun.messenger.data.MessageStatus
import org.maun.messenger.data.ReceiptLevel
import org.maun.messenger.network.Backoff
import org.maun.messenger.network.PushAddress
import org.maun.messenger.network.PushState
import org.maun.messenger.network.Ready
import org.maun.messenger.network.RelayConnection
import org.maun.messenger.network.RelayLink
import org.maun.messenger.network.RelayState
import org.maun.messenger.support.FakeDb

/**
 * Two (or three) phones' worth of the app's real messaging code, talking
 * through a real relay running on this machine. Only the database and the
 * secure hardware are stand-ins.
 *
 * Skipped unless MAUN_TEST_RELAY_URL is set; CI sets it.
 */
class ChatEngineTest {

    private lateinit var relayUrl: HttpUrl
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private inner class Phone(var withEngine: Boolean = true) {
        val identity: Identity = Identity.generate()
        val id = identity.id
        val db = FakeDb()
        val delivered = CopyOnWriteArrayList<JSONObject>()

        /** The wake-up address this phone wants, and the one it believes the relay has. */
        @Volatile
        var pushWanted: PushAddress? = null

        @Volatile
        var pushUploaded: PushAddress? = null

        private val push = object : PushState {
            override suspend fun wanted() = pushWanted
            override suspend fun uploaded() = pushUploaded
            override suspend fun setUploaded(address: PushAddress?) {
                pushUploaded = address
            }
        }

        /** Made anew by [restart]: the app going away and coming back. */
        var engine = newEngine()

        private fun newEngine() = ChatEngine(
            selfId = id,
            signal = SignalSessions(identity, SignalSessions.newRegistrationId(), db),
            dao = db,
            tx = db,
            scope = scope,
            retryMillis = 300,
            push = push,
        )

        /**
         * The app was killed and opened again: a new engine over the same
         * storage, with nothing in its memory. The old one is left without a
         * connection, the way a dead one would be.
         */
        fun restart() {
            engine.link = null
            engine = newEngine()
        }

        private val device = SoftwareDeviceSigner()
        private var registered = false
        private var connection: RelayConnection? = null

        /** The types of the requests the relay has answered with "saved". */
        val saved = CopyOnWriteArrayList<String>()

        /** Connects and waits until logged in. Returns what the relay said about the account. */
        fun online(): Ready {
            val created = RelayConnection(
                relayUrl = relayUrl,
                identity = identity,
                device = device,
                scope = scope,
                isRegistered = { registered },
                onLoggedIn = { registered = true },
                backoff = Backoff(baseMillis = 50, maxMillis = 200),
                onOnline = { if (withEngine) engine.onOnline() },
                onFrame = { frame ->
                    delivered += frame
                    if (withEngine) engine.onFrame(frame)
                },
                onOffline = { engine.onOffline() },
            )
            engine.link = object : RelayLink by created {
                override suspend fun request(frame: JSONObject, timeoutMillis: Long): JSONObject {
                    val answer = created.request(frame, timeoutMillis)
                    if (answer.optString("t") == "saved") saved += frame.optString("t")
                    return answer
                }
            }
            connection = created
            created.start()
            val state = runBlocking {
                withTimeout(15_000) { created.state.first { it is RelayState.Online || it is RelayState.Rejected } }
            }
            assertEquals(RelayState.Online, state)
            ready = created.ready
            return created.ready!!
        }

        fun offline() {
            connection?.stop()
            connection = null
        }

        /** Registers, publishes keys, and goes away again. */
        fun registeredAndOffline(): Phone {
            online()
            // Leaving before the relay has answered could lose the upload, and
            // the next login would then publish a second batch.
            eventually("keys on the relay") { "put_keys" in saved && "put_otks" in saved }
            offline()
            return this
        }

        /** Waits, as a phone woken to fetch its messages does, until there is nothing left to do. */
        fun caughtUp(timeoutMillis: Long = 15_000) = runBlocking { withTimeout(timeoutMillis) { engine.awaitCaughtUp() } }

        fun knows(vararg others: Phone): Phone {
            others.forEach { db.addContact(it.id) }
            return this
        }

        fun send(to: Phone, text: String) = runBlocking { engine.sendText(to.id, text) }

        fun read(from: Phone) = runBlocking { engine.markRead(from.id) }

        fun chat(with: Phone): List<MessageEntity> = db.messagesWith(with.id)

        fun texts(with: Phone): List<String> = chat(with).map { (if (it.outgoing) "> " else "< ") + it.text }

        fun statusOf(to: Phone, text: String): Int = chat(to).single { it.outgoing && it.text == text }.status

        /** What the relay said at the last login. */
        @Volatile
        var ready: Ready? = null

        /** The relay's file routes, with the credential from the last login. */
        fun files(onBanned: () -> Unit = {}) = FileApi(relayUrl, id, token = { ready?.filesToken }, onBanned = onBanned)
    }

    private fun eventually(what: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("did not happen in time: $what")
    }

    /** Gives something that should not happen a fair chance to happen. */
    private fun pause() = Thread.sleep(700)

    @Before
    fun requireRelay() {
        val configured = System.getenv("MAUN_TEST_RELAY_URL")
        assumeTrue("MAUN_TEST_RELAY_URL not set", !configured.isNullOrBlank())
        relayUrl = configured!!.toHttpUrl()
    }

    @After
    fun stopEverything() {
        scope.cancel()
    }

    @Test
    fun `a file goes up encrypted, its key travels in the message, and it comes down intact`() {
        val bob = Phone().registeredAndOffline()
        val alice = Phone().knows(bob)
        bob.knows(alice)
        val folder = File.createTempFile("maun", "files").apply {
            delete()
            mkdirs()
        }
        try {
            // A file of more than one piece, encrypted the way the app does it.
            val plain = ByteArray(FileCipher.CHUNK + 4321).also { java.util.Random(1).nextBytes(it) }
            val secret = FileCipher.newKey()
            val sealed = File(folder, "1.enc")
            sealed.outputStream().use { FileCipher.encrypt(plain.inputStream(), it, secret) }
            val record = Attachment(
                key = "",
                secret = Base64.getEncoder().encodeToString(secret),
                name = "notes.pdf",
                mime = "application/pdf",
                size = plain.size.toLong(),
                state = Attachment.UPLOADING,
            )

            alice.online()
            val message = runBlocking { alice.engine.addFile(bob.id, record) }
            pause()
            assertEquals("nothing is sent before the upload is done", 0, alice.db.outboxSize())
            assertEquals(MessageStatus.PENDING, alice.chat(bob).single().status)

            val key = alice.files().upload(sealed) { _, _ -> }
            runBlocking { alice.engine.sendFile(message.seq, record.copy(key = key)) }
            eventually("stored by the relay") { alice.chat(bob).single().status == MessageStatus.SENT }
            val mine = Attachment.fromLocal(alice.chat(bob).single().text)!!
            assertEquals(Attachment.READY, mine.state)
            assertEquals(key, mine.key)

            bob.online()
            eventually("arrived") { bob.chat(alice).size == 1 }
            val arrived = bob.chat(alice).single()
            assertEquals(MessageKind.FILE, arrived.kind)
            assertTrue(arrived.unread)
            val theirs = Attachment.fromLocal(arrived.text)!!
            assertEquals(record.copy(key = key, state = Attachment.REMOTE), theirs)
            eventually("delivered") { alice.chat(bob).single().status == MessageStatus.DELIVERED }
            // Opening the conversation tells the sender a file was seen, the same as a text.
            bob.read(alice)
            eventually("read") { alice.chat(bob).single().status == MessageStatus.READ }

            // What the relay holds is not the file: it opens only with the key from the message.
            val fetched = File(folder, "2.part")
            bob.files().download(theirs.key, fetched) { _, _ -> }
            assertEquals(FileCipher.encryptedSize(plain.size.toLong()), fetched.length())
            assertFalse(fetched.readBytes().contentEquals(plain))
            val opened = java.io.ByteArrayOutputStream()
            fetched.inputStream().use { FileCipher.decrypt(it, opened, theirs.secretBytes()) }
            assertTrue(opened.toByteArray().contentEquals(plain))

            // The credential belongs to one login: Alice's old one stops working when she logs in again.
            val stale = alice.ready!!.filesToken
            alice.offline()
            alice.online()
            assertFalse(stale == alice.ready!!.filesToken)
            val old = FileApi(relayUrl, alice.id, token = { stale }, onBanned = {})
            assertThrows(FileException::class.java) { old.download(key, File(folder, "3.part")) { _, _ -> } }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `the steps of a call go straight to a contact and are never kept for later`() {
        val bob = Phone().registeredAndOffline()
        val alice = Phone().knows(bob)
        bob.knows(alice)
        val stranger = Phone()
        val heard = CopyOnWriteArrayList<Triple<String, Payload.Call, Long>>()
        bob.engine.onCall = { from, call, age -> heard += Triple(from, call, age) }
        alice.online()
        bob.online()
        stranger.online()

        val offer = Payload.Call("call-1", Payload.CALL_OFFER, sdp = "v=0\r\n" + "a=candidate\r\n".repeat(150))
        assertTrue(runBlocking { alice.engine.sendCall(bob.id, offer, urgent = true) })
        val way = Payload.Call("call-1", Payload.CALL_ICE, candidate = Payload.Candidate("0", 0, "candidate:1 1 udp 1 198.51.100.7 50000 typ relay"))
        assertTrue(runBlocking { alice.engine.sendCall(bob.id, way, urgent = false) })
        eventually("both arrive, in order") { heard.map { it.second } == listOf(offer, way) }
        assertEquals(alice.id, heard[0].first)
        assertTrue("it arrived fresh", heard[0].third < 10_000)
        assertEquals(0, alice.db.outboxSize())
        // Nothing of a call is a message: the conversation stays empty.
        assertTrue(bob.chat(alice).isEmpty())

        // Someone Bob has not added cannot make his phone ring.
        runBlocking { stranger.engine.sendCall(bob.id, Payload.Call("call-2", Payload.CALL_OFFER, sdp = "v=0"), urgent = true) }
        pause()
        assertEquals(2, heard.size)

        // With no connection a step is not sent, and not saved up either.
        alice.offline()
        assertFalse(runBlocking { alice.engine.sendCall(bob.id, Payload.Call("call-1", Payload.CALL_HANGUP), urgent = false) })
        assertEquals(0, alice.db.outboxSize())
    }

    @Test
    fun `a relay without call servers says so in words`() {
        val alice = Phone()
        alice.online()
        val problem = assertThrows(RelayException::class.java) { runBlocking { alice.engine.callServers() } }
        assertTrue(problem.message!!.contains("not set up"))
    }

    @Test
    fun `an open relay asks for no login and names no member`() {
        val alice = Phone()
        val ready = alice.online()
        assertEquals(null, ready.email)
        assertTrue(ready.filesToken!!.length >= 20)
    }

    @Test
    fun `deleting every message leaves nothing to show and nothing to send`() {
        val bob = Phone().registeredAndOffline()
        val alice = Phone().knows(bob)
        alice.send(bob, "one")
        alice.send(bob, "two")
        assertEquals(2, alice.chat(bob).size)
        runBlocking { alice.engine.wipeMessages() }
        assertTrue(alice.chat(bob).isEmpty())
        assertEquals(0, alice.db.outboxSize())
        // And nothing leaves afterwards.
        alice.online()
        bob.online()
        pause()
        assertTrue(bob.chat(alice).isEmpty())
    }

    @Test
    fun `a message for someone offline arrives when they connect, and each step is reported`() {
        val bob = Phone().registeredAndOffline()
        val alice = Phone().knows(bob)
        bob.knows(alice)

        alice.online()
        alice.send(bob, "hello Bob")
        eventually("stored by the relay") { alice.statusOf(bob, "hello Bob") == MessageStatus.SENT }
        pause()
        assertEquals("nothing has reached Bob's phone yet", MessageStatus.SENT, alice.statusOf(bob, "hello Bob"))
        assertEquals(0, alice.db.outboxSize())
        assertTrue(bob.chat(alice).isEmpty())

        bob.online()
        eventually("arrived") { bob.texts(alice) == listOf("< hello Bob") }
        assertTrue(bob.chat(alice).single().unread)
        eventually("delivered") { alice.statusOf(bob, "hello Bob") == MessageStatus.DELIVERED }
        pause()
        assertEquals("Bob has not opened it", MessageStatus.DELIVERED, alice.statusOf(bob, "hello Bob"))

        bob.read(alice)
        assertFalse(bob.chat(alice).single().unread)
        eventually("read") { alice.statusOf(bob, "hello Bob") == MessageStatus.READ }
        eventually("nothing left to send") { alice.db.outboxSize() == 0 && bob.db.outboxSize() == 0 }

        // Reading again sends nothing more.
        val before = alice.delivered.size
        bob.read(alice)
        pause()
        assertEquals(before, alice.delivered.size)
    }

    @Test
    fun `a conversation keeps its order in both directions`() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob).online()
        bob.knows(alice).online()

        val fromAlice = (1..25).map { "a$it" }
        fromAlice.forEach { alice.send(bob, it) }
        eventually("all arrived") { bob.chat(alice).size == 25 }
        assertEquals(fromAlice.map { "< $it" }, bob.texts(alice))

        val fromBob = (1..10).map { "b$it" }
        fromBob.forEach { bob.send(alice, it) }
        eventually("replies arrived") { alice.chat(bob).count { !it.outgoing } == 10 }
        assertEquals(fromBob, alice.chat(bob).filter { !it.outgoing }.map { it.text })

        bob.read(alice)
        alice.read(bob)
        eventually("everything read") {
            alice.chat(bob).filter { it.outgoing }.all { it.status == MessageStatus.READ } &&
                bob.chat(alice).filter { it.outgoing }.all { it.status == MessageStatus.READ }
        }
        eventually("outboxes empty") { alice.db.outboxSize() == 0 && bob.db.outboxSize() == 0 }
    }

    @Test
    fun `messages written without a connection go out on the next one`() {
        val bob = Phone().registeredAndOffline()
        val alice = Phone().knows(bob)
        bob.knows(alice)

        listOf("one", "two", "three").forEach { alice.send(bob, it) }
        pause()
        assertTrue(alice.chat(bob).all { it.status == MessageStatus.PENDING })
        assertEquals(3, alice.db.outboxSize())
        // The number the phone waits on to ask Android for a connection.
        assertEquals(3, alice.engine.unsent.value)

        alice.online()
        eventually("sent") { alice.chat(bob).all { it.status == MessageStatus.SENT } }
        eventually("nothing left to send") { alice.engine.unsent.value == 0 }
        alice.offline()

        bob.online()
        eventually("arrived") { bob.chat(alice).size == 3 }
        assertEquals(listOf("< one", "< two", "< three"), bob.texts(alice))
    }

    @Test
    fun `what is waiting to go outlives the app being closed, and leaves without it being opened`() {
        val bob = Phone().registeredAndOffline()
        val alice = Phone().knows(bob)
        bob.knows(alice)

        listOf("one", "two", "three").forEach { alice.send(bob, it) }
        pause()
        assertEquals(3, alice.engine.unsent.value)

        // The app is killed before there is a network, and opened again later.
        alice.restart()
        eventually("the outbox is counted again from storage") { alice.engine.unsent.value == 3 }
        assertEquals(3, alice.db.outboxSize())

        // A connection made by the background work alone, the app closed.
        alice.online()
        eventually("nothing left to send") { alice.engine.unsent.value == 0 }
        alice.offline()

        bob.online()
        eventually("arrived") { bob.chat(alice).size == 3 }
        // Once each: the queue is emptied as it goes, not replayed.
        assertEquals(listOf("< one", "< two", "< three"), bob.texts(alice))
    }

    @Test
    fun `a message deleted before it left never leaves, and the ones around it still arrive in order`() {
        val bob = Phone().registeredAndOffline()
        val alice = Phone().knows(bob)
        bob.knows(alice)

        listOf("one", "not this", "three").forEach { alice.send(bob, it) }
        val unwanted = alice.chat(bob).single { it.text == "not this" }
        runBlocking { alice.engine.deleteMessage(unwanted.seq) }
        assertEquals(listOf("> one", "> three"), alice.texts(bob))
        assertEquals(2, alice.db.outboxSize())

        alice.online()
        eventually("sent") { alice.chat(bob).all { it.status == MessageStatus.SENT } }
        bob.online()
        eventually("arrived") { bob.chat(alice).size == 2 }
        pause()
        assertEquals(listOf("< one", "< three"), bob.texts(alice))
    }

    @Test
    fun `deleting a delivered message removes it here only, and later messages still get through`() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        bob.knows(alice)
        alice.online()
        bob.online()

        alice.send(bob, "first")
        eventually("delivered") { alice.statusOf(bob, "first") >= MessageStatus.DELIVERED }
        runBlocking { alice.engine.deleteMessage(alice.chat(bob).single().seq) }
        assertEquals(emptyList<String>(), alice.texts(bob))
        assertEquals(listOf("< first"), bob.texts(alice))

        // A late receipt for the deleted message changes nothing and breaks nothing.
        bob.read(alice)
        alice.send(bob, "second")
        eventually("second read") {
            bob.read(alice)
            alice.chat(bob).singleOrNull()?.status == MessageStatus.READ
        }
        assertEquals(listOf("< first", "< second"), bob.texts(alice))
    }

    @Test
    fun `a stranger's message waits as a request and tells them nothing`() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob).online()
        bob.online() // Bob has never heard of Alice.

        alice.send(bob, "hi, it's Alice")
        eventually("arrived") { bob.texts(alice) == listOf("< hi, it's Alice") }
        assertEquals(ReceiptLevel.NONE, bob.chat(alice).single().receipt)
        bob.read(alice) // Bob looks at the request.
        pause()
        assertEquals("a stranger learns nothing", MessageStatus.SENT, alice.statusOf(bob, "hi, it's Alice"))
        assertEquals(0, bob.db.outboxSize())
        assertThrows(IllegalStateException::class.java) { bob.send(alice, "who are you?") }

        // Bob accepts.
        bob.knows(alice)
        runBlocking { bob.engine.onContactAdded(alice.id) }
        eventually("delivered") { alice.statusOf(bob, "hi, it's Alice") == MessageStatus.DELIVERED }
        bob.read(alice)
        eventually("read") { alice.statusOf(bob, "hi, it's Alice") == MessageStatus.READ }
        bob.send(alice, "hello Alice")
        eventually("reply arrived") { alice.texts(bob) == listOf("> hi, it's Alice", "< hello Alice") }
    }

    @Test
    fun `a blocked sender is thrown away unread`() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob).online()
        bob.knows(alice).online()
        alice.send(bob, "first")
        eventually("arrived") { bob.chat(alice).size == 1 }

        runBlocking { bob.engine.block(alice.id) }
        assertTrue(bob.chat(alice).isEmpty())
        assertFalse(bob.db.isContact(alice.id))

        val seen = bob.delivered.size
        alice.send(bob, "second")
        eventually("the relay delivered it") { bob.delivered.size > seen }
        eventually("stored by the relay") { alice.statusOf(bob, "second") == MessageStatus.SENT }
        pause()
        assertTrue(bob.chat(alice).isEmpty())
        assertEquals(MessageStatus.SENT, alice.statusOf(bob, "second"))

        // It was acknowledged, so it does not come back on the next connection.
        bob.offline()
        val before = bob.delivered.size
        bob.online()
        pause()
        assertEquals(before, bob.delivered.size)

        runBlocking { bob.engine.unblock(alice.id) }
        alice.send(bob, "third")
        eventually("arrives as a request") { bob.texts(alice) == listOf("< third") }
    }

    @Test
    fun `a delivery that could not be saved comes again, exactly once`() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob).online()
        bob.knows(alice).online()

        // Bob's phone dies while saving: the transaction is rolled back.
        bob.db.failNextMessageInsert = true
        alice.send(bob, "do not lose me")
        eventually("the relay delivered it") { bob.delivered.isNotEmpty() }
        eventually("stored by the relay") { alice.statusOf(bob, "do not lose me") == MessageStatus.SENT }
        pause()
        assertTrue(bob.chat(alice).isEmpty())
        assertFalse("the session must not have moved without the message", bob.db.hasSession(alice.id))

        bob.offline()
        bob.online()
        eventually("arrived the second time") { bob.texts(alice) == listOf("< do not lose me") }
        eventually("delivered") { alice.statusOf(bob, "do not lose me") == MessageStatus.DELIVERED }

        // And the session works in both directions afterwards.
        bob.send(alice, "got it")
        eventually("reply arrived") { alice.texts(bob) == listOf("> do not lose me", "< got it") }
        alice.send(bob, "good")
        eventually("arrived") { bob.texts(alice).last() == "< good" }
        assertEquals(3, bob.chat(alice).size)
    }

    @Test
    fun `the same delivery twice is saved once`() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob).online()
        bob.knows(alice).online()
        alice.send(bob, "one")
        alice.send(bob, "two")
        eventually("arrived") { bob.chat(alice).size == 2 }
        bob.send(alice, "three")
        eventually("arrived") { alice.chat(bob).size == 3 }
        eventually("settled") { alice.db.outboxSize() == 0 && bob.db.outboxSize() == 0 }

        // The relay never got the acknowledgements and sends everything again.
        val again = bob.delivered.toList()
        assertTrue(again.size >= 2)
        runBlocking { again.forEach { bob.engine.onFrame(it) } }
        assertEquals(listOf("< one", "< two", "> three"), bob.texts(alice))
        assertTrue(bob.chat(alice).none { it.kind == MessageKind.UNREADABLE })
        pause()
        assertEquals("no second receipt", 0, bob.db.outboxSize())
    }

    @Test
    fun `a message nobody can decrypt leaves a note for a contact only`() {
        val alice = Phone()
        val bob = Phone()
        val carol = Phone()
        bob.knows(alice).online()
        alice.online()
        carol.online()

        val garbage = java.util.Base64.getEncoder().encodeToString(ByteArray(200) { 5 })
        fun frame(from: Phone, seq: Long) =
            JSONObject().put("t", "msg").put("seq", seq).put("from", from.id).put("kind", 2).put("body", garbage)
        runBlocking {
            bob.engine.onFrame(frame(alice, 1))
            bob.engine.onFrame(frame(carol, 2))
            bob.engine.onFrame(frame(bob, 3))
            bob.engine.onFrame(JSONObject().put("t", "msg").put("seq", 4).put("from", "nonsense").put("body", garbage))
            bob.engine.onFrame(JSONObject().put("t", "msg").put("seq", 5).put("from", alice.id).put("body", "***"))
        }
        assertEquals(listOf(MessageKind.UNREADABLE), bob.chat(alice).map { it.kind })
        assertTrue(bob.chat(carol).isEmpty())
        assertTrue(bob.chat(bob).isEmpty())
        assertEquals(0, bob.db.outboxSize())
    }

    @Test
    fun `writing to an ID nobody registered waits and says why`() {
        val alice = Phone()
        val nobody = Phone()
        alice.knows(nobody).online()
        alice.send(nobody, "anyone there?")
        eventually("explained") { alice.engine.waiting.value[nobody.id]?.contains("not registered") == true }
        assertEquals(MessageStatus.PENDING, alice.statusOf(nobody, "anyone there?"))
        assertEquals(1, alice.db.outboxSize())

        // Other conversations are not held up by it.
        val bob = Phone()
        bob.knows(alice).online()
        alice.knows(bob)
        alice.send(bob, "hello")
        eventually("arrived") { bob.texts(alice) == listOf("< hello") }

        // Forgetting the contact drops what was queued for them.
        runBlocking { alice.engine.forget(nobody.id) }
        assertTrue(alice.chat(nobody).isEmpty())
        assertEquals(null, alice.engine.waiting.value[nobody.id])
        eventually("outbox empty") { alice.db.outboxSize() == 0 }
    }

    @Test
    fun `someone who has not published keys is reached as soon as they do`() {
        val alice = Phone()
        val old = Phone(withEngine = false) // registered with a version that had no messaging
        old.online()
        old.offline()
        alice.knows(old).online()

        alice.send(old, "are you there?")
        eventually("explained") { alice.engine.waiting.value[old.id]?.contains("newer version") == true }
        assertEquals(MessageStatus.PENDING, alice.statusOf(old, "are you there?"))

        // They update: same identity, same device key, now with messaging.
        old.withEngine = true
        old.knows(alice).online()
        eventually("arrived") { old.texts(alice) == listOf("< are you there?") }
        eventually("no longer waiting") { alice.engine.waiting.value.isEmpty() }
        eventually("delivered") { alice.statusOf(old, "are you there?") == MessageStatus.DELIVERED }
    }

    @Test
    fun `one-time keys are published, spent one per new conversation, and counted`() {
        val bob = Phone().registeredAndOffline()
        val ready = bob.online()
        assertTrue(ready.hasKeys)
        assertEquals(ChatEngine.ONE_TIME_KEY_BATCH, ready.oneTimeKeys)
        pause()
        assertEquals("enough on the relay: no top-up", ChatEngine.ONE_TIME_KEY_BATCH, bob.db.preKeyCount(SignalStorage.ONE_TIME))

        val alice = Phone()
        alice.knows(bob).online()
        alice.send(bob, "hi")
        eventually("arrived") { bob.chat(alice).size == 1 }
        assertEquals(ChatEngine.ONE_TIME_KEY_BATCH - 1, bob.db.preKeyCount(SignalStorage.ONE_TIME))
        alice.send(bob, "again")
        eventually("arrived") { bob.chat(alice).size == 2 }
        assertEquals("only the first message of a conversation spends a key", ChatEngine.ONE_TIME_KEY_BATCH - 1, bob.db.preKeyCount(SignalStorage.ONE_TIME))

        bob.offline()
        assertEquals(ChatEngine.ONE_TIME_KEY_BATCH - 1, bob.online().oneTimeKeys)
    }

    @Test
    fun `empty and oversized messages are refused before anything is saved`() {
        val alice = Phone()
        val bob = Phone()
        alice.knows(bob)
        assertThrows(IllegalArgumentException::class.java) { alice.send(bob, "   \n ") }
        assertThrows(IllegalArgumentException::class.java) { alice.send(bob, "x".repeat(Payload.MAX_TEXT_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { alice.send(bob, "म".repeat(Payload.MAX_TEXT_BYTES / 3 + 1)) }
        assertTrue(alice.chat(bob).isEmpty())
        assertEquals(0, alice.db.outboxSize())

        // The longest allowed message makes it through the relay's size limit,
        // even as the first message of a conversation, which is the largest kind.
        bob.knows(alice).online()
        alice.online()
        val longest = "म".repeat(Payload.MAX_TEXT_BYTES / 3) + "x".repeat(Payload.MAX_TEXT_BYTES % 3)
        alice.send(bob, longest)
        eventually("arrived") { bob.chat(alice).singleOrNull()?.text == longest }
    }

    // ---- Woken up to fetch ----------------------------------------------------

    @Test
    fun `a phone woken to fetch takes everything in, confirms it, and can leave at once`() {
        val alice = Phone().registeredAndOffline()
        val bob = Phone().registeredAndOffline()
        alice.knows(bob)
        bob.knows(alice)

        alice.online()
        val texts = (1..7).map { "message $it" }
        texts.forEach { alice.send(bob, it) }
        eventually("stored on the relay") { texts.all { alice.statusOf(bob, it) == MessageStatus.SENT } }

        // Bob's phone is woken: it connects, and leaves the moment it has caught up.
        val ready = bob.online()
        assertEquals(7, ready.queued)
        bob.caughtUp()
        bob.offline()

        // Nothing was left behind, in either direction.
        assertEquals(texts.map { "< $it" }, bob.texts(alice))
        assertEquals(0, bob.db.outboxSize())
        eventually("delivery receipts") { texts.all { alice.statusOf(bob, it) == MessageStatus.DELIVERED } }
        assertEquals(0, bob.online().queued)
        bob.offline()
        alice.offline()
    }

    @Test
    fun `there is nothing to wait for when nothing is waiting, and no end to it without a connection`() {
        val bob = Phone().registeredAndOffline()
        try {
            bob.caughtUp(timeoutMillis = 500)
            throw AssertionError("caught up without a connection")
        } catch (_: TimeoutCancellationException) {
        }
        bob.online()
        bob.caughtUp()
        bob.offline()
        try {
            bob.caughtUp(timeoutMillis = 500)
            throw AssertionError("still caught up after the connection ended")
        } catch (_: TimeoutCancellationException) {
        }
    }

    @Test
    fun `the relay is given the wake-up address once, told when it changes, and told when it is gone`() {
        // Nobody writes to this phone, so the relay never calls the address.
        fun address(topic: String) = PushAddress.fromWebPush(
            "https://push.example.org/$topic",
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
            "BTBZMqHH6r4Tts7J_aSIgg",
        )!!
        val bob = Phone()
        bob.pushWanted = address("up1")
        val first = bob.online()
        assertFalse(first.hasPush)
        assertNull(first.pushStatus)
        // The key the relay will sign this phone's wake-ups with, for the push app.
        val relayKey = PushAddress.keyForPushApp(first.relayKey)
        assertEquals(87, relayKey!!.length)
        bob.caughtUp()
        assertEquals(1, bob.saved.count { it == "put_push" })
        assertEquals(address("up1"), bob.pushUploaded)
        bob.offline()

        // The relay remembers; the phone does not send it again. The signing key stays the same.
        val second = bob.online()
        assertTrue(second.hasPush)
        assertEquals(relayKey, PushAddress.keyForPushApp(second.relayKey))
        bob.caughtUp()
        assertEquals(1, bob.saved.count { it == "put_push" })

        // The push app hands out a new address while connected.
        bob.pushWanted = address("up2")
        bob.engine.pushChanged()
        eventually("new address uploaded") { bob.pushUploaded == address("up2") }
        assertEquals(2, bob.saved.count { it == "put_push" })
        bob.offline()

        // The push app is removed.
        bob.pushWanted = null
        assertTrue(bob.online().hasPush)
        bob.caughtUp()
        assertEquals(listOf("drop_push"), bob.saved.filter { it == "drop_push" })
        assertNull(bob.pushUploaded)
        bob.offline()
        assertFalse(bob.online().hasPush)
        bob.caughtUp()
        assertEquals(1, bob.saved.count { it == "drop_push" })
        assertNull(bob.engine.pushProblem.value)
        bob.offline()
    }

    @Test
    fun `an address the relay will not call is reported and not remembered`() {
        val bob = Phone()
        // A relay must never be made to call into a private network.
        bob.pushWanted = PushAddress(
            "https://192.168.1.10/up1",
            "BCVxsr7N/eNgVRqvHtD0zTZsEc6+VV+JvLexhqUzORcxaOzi6+AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4=",
            "BTBZMqHH6r4Tts7J/aSIgg==",
        )
        bob.online()
        bob.caughtUp()
        assertNotNull(bob.engine.pushProblem.value)
        assertNull(bob.pushUploaded)
        bob.offline()
        assertFalse(bob.online().hasPush)
        bob.offline()
    }
}
