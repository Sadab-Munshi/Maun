package org.maun.messenger

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maun.messenger.crypto.FileCipher
import org.maun.messenger.network.AvatarApi
import org.maun.messenger.support.FakeDb
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Profile pictures, end to end but for the screen: a picture is sealed before
 * it leaves, the relay only ever holds sealed bytes, contacts are told by
 * message, and what arrives is kept so that it is fetched once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProfilePicturesTest {

    private lateinit var context: Context
    private lateinit var server: HttpServer
    private lateinit var folder: File

    /** What the stand-in relay holds, by account: the sealed picture and its version. */
    private val stored = ConcurrentHashMap<String, ByteArray>()
    private val versions = ConcurrentHashMap<String, String>()
    private val downloads = AtomicInteger()
    private val deletions = AtomicInteger()
    private var uploads = 0

    private val self = "3c".repeat(32)
    private val bob = "7a".repeat(32)

    private lateinit var db: FakeDb
    private val asked = mutableListOf<String>()
    private val sent = mutableListOf<Pair<String, Payload.Avatar>>()
    private var changed = 0

    private fun startRelay() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/avatars") { exchange ->
            val path = exchange.requestURI.path.removePrefix("/v1/avatars").trim('/')
            when (exchange.requestMethod) {
                "PUT" -> {
                    val body = exchange.requestBody.readBytes()
                    uploads++
                    val ver = "%016x".format(java.util.Random().nextLong())
                    stored[self] = body
                    versions[self] = ver
                    answer(exchange, 201, """{"ver":"$ver","size":${body.size}}""")
                }
                "GET" -> {
                    downloads.incrementAndGet()
                    val body = stored[path]
                    if (body == null) {
                        answer(exchange, 404, """{"error":"no_avatar"}""")
                    } else {
                        exchange.responseHeaders.add("X-Maun-Avatar-Version", versions[path])
                        exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                }
                "DELETE" -> {
                    deletions.incrementAndGet()
                    stored.remove(path.ifEmpty { self })
                    exchange.sendResponseHeaders(204, -1)
                    exchange.close()
                }
                else -> answer(exchange, 405, """{"error":"nope"}""")
            }
        }
        server.start()
    }

    private fun answer(exchange: com.sun.net.httpserver.HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Before
    fun start() {
        context = ApplicationProvider.getApplicationContext()
        folder = File.createTempFile("maun", "pictures").apply {
            delete()
            mkdirs()
        }
        db = FakeDb()
        startRelay()
        Avatars.remove(context, Avatars.SELF)
        Avatars.removeLocal(context, self)
    }

    @After
    fun stop() {
        server.stop(0)
        folder.deleteRecursively()
        Avatars.remove(context, Avatars.SELF)
    }

    private fun api(port: Int = server.address.port) = AvatarApi(
        relayUrl = "http://127.0.0.1:$port/".toHttpUrl(),
        selfId = self,
        token = { "tok-en_123" },
        onBanned = {},
    )

    private fun pictures(
        api: AvatarApi = api(),
        contacts: List<String> = listOf(bob),
    ) = ProfilePictures(
        context = context,
        api = api,
        meta = db.meta(),
        contacts = { contacts },
        ask = { peer -> asked += peer },
        send = { peer, picture -> sent += peer to picture },
        whenOnline = {},
        onChanged = { changed++ },
    )

    /** A JPEG, as a phone's camera would make it, as a file to pick. */
    private fun photo(): Uri {
        val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val file = File(folder, "chosen.jpg").apply { writeBytes(jpeg) }
        return Uri.fromFile(file)
    }

    /** [bytes] sealed with [key], as another phone sends them. */
    private fun seal(bytes: ByteArray, key: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> FileCipher.encrypt(bytes.inputStream(), out, key) }.toByteArray()

    @Test
    fun `a picture is sealed before it is put on the relay, and contacts are told`() = runBlocking {
        val pictures = pictures()
        assertNull(pictures.publish(photo()))

        // Kept here, and said to the one contact this phone has.
        assertTrue(Avatars.has(context, Avatars.SELF))
        assertEquals(1, uploads)
        assertEquals(listOf(bob), sent.map { it.first })
        val told = sent.single().second
        assertTrue(Payload.isAvatarVersion(told.version))
        assertTrue("the screens are told to redraw", changed >= 1)

        // What the relay holds is not the picture: it opens only with the key
        // from the message.
        val kept = stored.getValue(self)
        assertFalse(kept.contentEquals(Avatars.bytes(context, Avatars.SELF)))
        val opened = ByteArrayOutputStream().also { out ->
            FileCipher.decrypt(kept.inputStream(), out, Base64.decode(told.key, Base64.NO_WRAP))
        }.toByteArray()
        assertArrayEquals(Avatars.bytes(context, Avatars.SELF), opened)

        // The version the relay gave is the one contacts are told about.
        assertEquals(versions.getValue(self), told.version)
        assertFalse(pictures.awaitingUpload)
    }

    @Test
    fun `a picture reaches no one while there is no connection, and is kept for later`() = runBlocking {
        // A port nobody is listening on.
        val offline = pictures(api = api(port = 1))
        assertNull(offline.publish(photo()))

        assertTrue("the picture is still shown here", Avatars.has(context, Avatars.SELF))
        assertTrue(offline.awaitingUpload)
        assertTrue("nothing is said about a picture the relay does not have", sent.isEmpty())
        // A contact asking now is told plainly that this phone has no picture
        // to share, rather than being given a key to nothing.
        assertNull(offline.mine())
    }

    @Test
    fun `a picture a contact announces is fetched once and kept`() = runBlocking {
        // Bob's phone: a picture, sealed with a key of its own, then the message.
        val key = FileCipher.newKey()
        val jpeg = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, out)
        }.toByteArray()
        val sealed = seal(jpeg, key)
        stored[bob] = sealed
        versions[bob] = "0123456789abcdef"

        val pictures = pictures()
        pictures.onSignal(bob, Payload.Avatar("0123456789abcdef", Base64.encodeToString(key, Base64.NO_WRAP), jpeg.size.toLong()))

        assertTrue("the picture is on this phone now", Avatars.has(context, bob))
        assertEquals(1, downloads.get())

        // Said again (or asked for by another path): nothing is fetched twice.
        pictures.onSignal(bob, Payload.Avatar("0123456789abcdef", Base64.encodeToString(key, Base64.NO_WRAP), jpeg.size.toLong()))
        assertEquals(1, downloads.get())

        // A new version is fetched.
        versions[bob] = "fedcba9876543210"
        pictures.onSignal(bob, Payload.Avatar("fedcba9876543210", Base64.encodeToString(key, Base64.NO_WRAP), jpeg.size.toLong()))
        assertEquals(2, downloads.get())
    }

    @Test
    fun `a contact asking for a picture is answered with this phone's own`() = runBlocking {
        val pictures = pictures()
        assertNull(pictures.publish(photo()))
        val mine = pictures.mine()
        assertNotNull(mine)
        sent.clear()

        pictures.onSignal(bob, Payload.Avatar(Payload.AVATAR_ASK))

        assertEquals(listOf(bob), sent.map { it.first })
        assertEquals(mine, sent.single().second)
    }

    @Test
    fun `a contact asking when there is no picture is told there is none`() = runBlocking {
        val pictures = pictures()
        // Nothing was ever chosen on this phone.
        pictures.onSignal(bob, Payload.Avatar(Payload.AVATAR_ASK))
        assertEquals(Payload.AVATAR_NONE, sent.single().second.version)
    }

    @Test
    fun `a picture taken down by its owner goes away here too`() = runBlocking {
        val key = FileCipher.newKey()
        val jpeg = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, out)
        }.toByteArray()
        stored[bob] = seal(jpeg, key)
        versions[bob] = "aaaaaaaaaaaaaaaa"

        val pictures = pictures()
        pictures.onSignal(bob, Payload.Avatar("aaaaaaaaaaaaaaaa", Base64.encodeToString(key, Base64.NO_WRAP), jpeg.size.toLong()))
        assertTrue(Avatars.has(context, bob))

        pictures.onSignal(bob, Payload.Avatar(Payload.AVATAR_NONE))
        assertFalse(Avatars.has(context, bob))
    }

    @Test
    fun `taking one's own picture down removes it here, from the relay, and from contacts`() = runBlocking {
        val pictures = pictures()
        assertNull(pictures.publish(photo()))
        sent.clear()

        pictures.remove()

        assertFalse(Avatars.has(context, Avatars.SELF))
        assertEquals(1, deletions.get())
        assertNull(Avatars.bytes(context, Avatars.SELF))
        assertEquals(listOf(bob to Payload.AVATAR_NONE), sent.toList())
        // The next person to ask is told the truth.
        assertNull(pictures.mine())
    }

    @Test
    fun `catching up asks about contacts unknown and keeps the rest as they are`() = runBlocking {
        val pictures = pictures()
        pictures.sync()
        assertEquals(listOf(bob), asked)
        assertEquals(0, downloads.get())
    }

    @Test
    fun `a picture that arrived is stored under the real picture, never as a local one`() = runBlocking {
        val key = FileCipher.newKey()
        val jpeg = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, out)
        }.toByteArray()
        stored[bob] = seal(jpeg, key)
        versions[bob] = "0123456789abcdee"

        pictures().onSignal(bob, Payload.Avatar("0123456789abcdee", Base64.encodeToString(key, Base64.NO_WRAP), jpeg.size.toLong()))

        assertTrue(Avatars.has(context, bob))
        assertFalse("only this phone's own notes are local", Avatars.hasLocal(context, bob))
        assertTrue(Avatars.shown(context, bob) == Avatars.file(context, bob))
    }
}
