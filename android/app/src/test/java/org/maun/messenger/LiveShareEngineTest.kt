package org.maun.messenger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.maun.messenger.crypto.Identity
import org.maun.messenger.crypto.SignalSessions
import org.maun.messenger.data.MessageKind
import org.maun.messenger.data.MessageStatus
import org.maun.messenger.support.FakeDb

/**
 * What a share looks like in the phone's own storage: one message, one thing
 * to send, and a key that is in both but never in anything the relay reads.
 */
class LiveShareEngineTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = FakeDb()
    private val identity = Identity.generate()
    private val peer = Identity.generate().id
    private val now = 1_794_700_000_000L
    private lateinit var engine: ChatEngine

    private val share = "3f2c9a1e7b4d4c0e9a1f2b3c4d5e6f70"
    private val key = LiveSeal.newKey()
    private val until = now + 15 * 60_000L

    @Before
    fun setUp() {
        db.addContact(peer)
        engine = ChatEngine(
            selfId = identity.id,
            signal = SignalSessions(identity, SignalSessions.newRegistrationId(), db),
            dao = db,
            tx = db,
            scope = scope,
            now = { now },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `starting a share writes one card and one message carrying its key`() = runBlocking {
        engine.addLiveShare(peer, share, key, 15, until)

        val card = db.messagesWith(peer).single()
        assertEquals(MessageKind.LOCATION, card.kind)
        assertTrue(card.outgoing)
        assertEquals(MessageStatus.PENDING, card.status)
        val record = LiveShare.fromLocal(card.text)!!
        assertEquals(share, record.share)
        assertEquals(15, record.minutes)
        assertEquals(until, record.until)
        assertEquals(LivePhase.SHARING, record.phase(outgoing = true, now = now))

        // What the other phone is told: where the share is, and the key that
        // opens it — nothing of where anybody is.
        val queued = db.outboxFor(peer).single()
        val payload = Payload.decode(queued.payload) as Payload.LiveLocation
        assertEquals(Payload.LIVE_SHARE, payload.type)
        assertEquals(share, payload.share)
        assertEquals(key, payload.key)
        assertEquals(until, payload.until)
        assertEquals(record.share, payload.share)
    }

    @Test
    fun `the key is in the message and nowhere else that is sent`() = runBlocking {
        engine.addLiveShare(peer, share, key, 15, until)
        val queued = db.outboxFor(peer).single()
        // The relay reads frames, not this: what it would see of the message
        // is a Signal ciphertext. The plaintext here is what is encrypted, and
        // it is the only place the key appears.
        val payload = Payload.decode(queued.payload) as Payload.LiveLocation
        assertEquals(key, payload.key)
        // Nothing of the key survives in the card's own record without it.
        val card = db.messagesWith(peer).single()
        assertTrue(card.text.contains(key))
        assertNull(LiveShare.fromLocal(card.text)?.key?.takeIf { it != key })
    }

    @Test
    fun `ending a share moves the card and tells the other phone without the key`() = runBlocking {
        engine.addLiveShare(peer, share, key, 60, until)
        val before = db.outboxFor(peer).size

        engine.endLiveShare(peer, share)

        val record = LiveShare.fromLocal(db.messagesWith(peer).single().text)!!
        assertEquals(LiveShare.STOPPED, record.state)
        assertEquals(LivePhase.ENDED, record.phase(outgoing = true, now = now))

        val queued = db.outboxFor(peer)
        assertEquals(before + 1, queued.size)
        val stop = Payload.decode(queued.last().payload) as Payload.LiveLocation
        assertEquals(Payload.LIVE_STOP, stop.type)
        assertEquals(share, stop.share)
        // A message that only says "it is over" carries no key.
        assertEquals("", stop.key)
    }

    @Test
    fun `a share still running is remembered for when the app comes back`() = runBlocking {
        engine.addLiveShare(peer, share, key, 15, until)
        val running = engine.runningShares()
        assertEquals(1, running.size)
        assertEquals(share, LiveShare.fromLocal(running.single().text)?.share)

        engine.endLiveShare(peer, share)
        val ended = engine.runningShares().single()
        assertEquals(
            LivePhase.ENDED,
            LiveShare.fromLocal(ended.text)!!.phase(outgoing = true, now = now),
        )
    }

    @Test
    fun `a share is only for a contact`() = runBlocking {
        val stranger = Identity.generate().id
        val failed = try {
            engine.addLiveShare(stranger, share, key, 15, until)
            false
        } catch (_: IllegalStateException) {
            true
        }
        assertTrue(failed)
        assertTrue(db.messagesWith(stranger).isEmpty())
    }
}
