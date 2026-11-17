package org.maun.messenger

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import org.maun.messenger.crypto.FileCipher
import org.maun.messenger.data.MetaDao
import org.maun.messenger.data.MetaEntry
import org.maun.messenger.network.AvatarApi

/**
 * Profile pictures, kept the way Maun keeps everything else.
 *
 * A picture is shrunk on the phone that owns it, sealed with a key of its own,
 * and put on the relay under the owner's ID. The key travels only inside the
 * end-to-end encrypted messages between that person and their contacts, so the
 * relay stores a picture it cannot open. Nothing else about the picture is
 * written down anywhere: no name, no address, no list of who may see it.
 *
 * Contacts learn of a new picture in the same way they learn of anything else:
 * a message that only their phone can read says "my picture is now
 * <version>", or asks "what picture do you have?". A phone that is offline
 * keeps the picture it already has and catches up later; a contact who has
 * never shown a picture keeps their initial.
 */
class ProfilePictures(
    private val context: Context,
    private val api: AvatarApi,
    private val meta: MetaDao,
    /** The people this phone talks to, whose pictures are kept up to date. */
    private val contacts: () -> List<String>,
    /** Queues the question "which picture do you have?" for one person. */
    private val ask: suspend (String) -> Unit,
    /** Queues a picture (or its removal) for one person. */
    private val send: suspend (String, Payload.Avatar) -> Unit,
    /** Returns once the relay is connected, or after waiting long enough for it. */
    private val whenOnline: suspend () -> Unit = {},
    /** A picture changed on this phone: every avatar on screen should be redrawn. */
    private val onChanged: () -> Unit = {},
) {
    private val appContext = context.applicationContext

    /** This phone's own picture, as it is on the relay. Empty when there is none. */
    @Volatile
    private var own: String = ""

    /** This phone's own picture, waiting for a connection before it can be shared. */
    @Volatile
    private var pending: Boolean = false

    /** Whether the picture in storage is the one on the relay, or still to go. */
    val awaitingUpload: Boolean get() = pending

    /**
     * What to say when a contact asks which picture this phone has. Null when
     * there is nothing to say: no picture, or one that is not on the relay yet.
     */
    fun mine(): Payload.Avatar? = if (pending) null else decode(own)?.takeIf { it.version != Payload.AVATAR_NONE }

    /** Reads what this phone knows about its own picture. Call once, at startup. */
    suspend fun load() = withContext(Dispatchers.IO) {
        own = meta.get(KEY_SELF).orEmpty()
        pending = meta.get(KEY_SELF_PENDING) == "1"
        if (pending && Avatars.bytes(appContext, Avatars.SELF) == null) {
            // The picture itself is gone: nothing is waiting for a connection any more.
            pending = false
            meta.put(MetaEntry(KEY_SELF_PENDING, ""))
        }
    }

    /**
     * Keeps the picture at [uri] as this phone's own and tells its contacts.
     * Returns why it could not be read, or null when it has been kept (it is
     * shared at once when the relay can be reached, and otherwise as soon as
     * it can).
     */
    suspend fun publish(uri: android.net.Uri): String? {
        if (!Avatars.save(appContext, Avatars.SELF, uri)) return "That picture could not be read."
        // Shown at once, everywhere in the app, before it is anywhere else.
        onChanged()
        pending = true
        withContext(Dispatchers.IO) { meta.put(MetaEntry(KEY_SELF_PENDING, "1")) }
        upload()
        return null
    }

    /** Takes this phone's picture down, here and for everyone. */
    suspend fun remove() {
        Avatars.remove(appContext, Avatars.SELF)
        own = ""
        pending = false
        withContext(Dispatchers.IO) {
            meta.put(MetaEntry(KEY_SELF, ""))
            meta.put(MetaEntry(KEY_SELF_PENDING, ""))
        }
        onChanged()
        val gone = Payload.Avatar(Payload.AVATAR_NONE)
        contacts().forEach { peer -> send(peer, gone) }
        runCatching {
            whenOnline()
            withContext(Dispatchers.IO) { api.delete() }
        }
    }

    /**
     * Seals the picture on this phone and puts it on the relay, then tells
     * contacts about it. A picture that cannot be put on right now stays on
     * this phone and is tried again at the next connection.
     */
    suspend fun upload() {
        val bytes = Avatars.bytes(appContext, Avatars.SELF) ?: return
        val key = FileCipher.newKey()
        val sealed = File(appContext.cacheDir, "avatar-out.tmp")
        val version = try {
            sealed.outputStream().buffered().use { out ->
                FileCipher.encrypt(bytes.inputStream(), out, key)
            }
            whenOnline()
            withContext(Dispatchers.IO) { api.upload(sealed) }
        } catch (_: IOException) {
            return
        } catch (_: GeneralSecurityException) {
            return
        } finally {
            sealed.delete()
        }
        val picture = Payload.Avatar(
            version = version,
            key = Base64.encodeToString(key, Base64.NO_WRAP),
            size = bytes.size.toLong(),
        )
        own = encode(picture)
        pending = false
        withContext(Dispatchers.IO) {
            meta.put(MetaEntry(KEY_SELF, own))
            meta.put(MetaEntry(KEY_SELF_PENDING, ""))
        }
        // Everyone this phone talks to hears about it; those who are listening
        // fetch it, and the rest when they next open Maun.
        contacts().forEach { peer -> send(peer, picture) }
        onChanged()
    }

    /**
     * One picture message from [peer]: a question, a picture, or the news that
     * it is gone.
     */
    suspend fun onSignal(peer: String, picture: Payload.Avatar) {
        when {
            picture.version == Payload.AVATAR_ASK -> {
                send(peer, mine() ?: Payload.Avatar(Payload.AVATAR_NONE))
            }
            picture.version == Payload.AVATAR_NONE -> {
                Avatars.remove(appContext, peer)
                withContext(Dispatchers.IO) {
                    meta.put(MetaEntry(recordKey(peer), ""))
                    meta.put(MetaEntry(cachedKey(peer), ""))
                }
                onChanged()
            }
            else -> {
                val before = withContext(Dispatchers.IO) { meta.get(recordKey(peer)).orEmpty() }
                if (before != encode(picture)) {
                    withContext(Dispatchers.IO) { meta.put(MetaEntry(recordKey(peer), encode(picture))) }
                }
                if (withContext(Dispatchers.IO) { meta.get(cachedKey(peer)) } != picture.version) {
                    refresh(peer)
                }
            }
        }
    }

    /**
     * Fetches the picture of [peer] if the one on this phone is not the one
     * its owner has now. Returns whether a new picture was kept. Failing is
     * not an error: the old picture stays, and the next connection tries again.
     */
    suspend fun refresh(peer: String): Boolean {
        val record = withContext(Dispatchers.IO) { decode(meta.get(recordKey(peer)).orEmpty()) } ?: return false
        if (record.version == Payload.AVATAR_NONE) return false
        if (withContext(Dispatchers.IO) { meta.get(cachedKey(peer)) } == record.version) return false
        val key = try {
            Base64.decode(record.key, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val sealed = File(appContext.cacheDir, "avatar-in.tmp")
        return try {
            whenOnline()
            val version = withContext(Dispatchers.IO) { api.download(peer, sealed) }
            if (version != record.version) {
                // The owner has changed it again; their message will come with the new key.
                return false
            }
            val plain = ByteArrayOutputStream()
            sealed.inputStream().buffered().use { input -> FileCipher.decrypt(input, plain, key) }
            if (!Avatars.store(appContext, peer, plain.toByteArray())) return false
            withContext(Dispatchers.IO) { meta.put(MetaEntry(cachedKey(peer), record.version)) }
            onChanged()
            true
        } catch (_: IOException) {
            false
        } catch (_: GeneralSecurityException) {
            false
        } finally {
            sealed.delete()
        }
    }

    /**
     * Catches up with every contact: asks those whose picture this phone has
     * never been told about, and fetches the ones that changed. Also retries
     * this phone's own picture if it never left.
     */
    suspend fun sync() {
        if (pending) upload()
        for (peer in contacts()) {
            val record = withContext(Dispatchers.IO) { meta.get(recordKey(peer)).orEmpty() }
            if (record.isEmpty()) ask(peer) else refresh(peer)
        }
    }

    /** Asks [peer] which picture they have; used when they are added or the app starts. */
    suspend fun request(peer: String) = ask(peer)

    private fun recordKey(peer: String) = "$KEY_PEER$peer"

    private fun cachedKey(peer: String) = "$KEY_CACHED$peer"

    private fun encode(picture: Payload.Avatar): String = JSONObject()
        .put("ver", picture.version)
        .put("key", picture.key)
        .put("size", picture.size)
        .toString()

    /** Reads a picture record, or null when there is none (or it makes no sense). */
    private fun decode(text: String): Payload.Avatar? {
        if (text.isEmpty()) return null
        val json = try {
            JSONObject(text)
        } catch (_: JSONException) {
            return null
        }
        val version = json.optString("ver")
        if (version != Payload.AVATAR_NONE && !Payload.isAvatarVersion(version)) return null
        val key = json.optString("key")
        if (version != Payload.AVATAR_NONE && key.isEmpty()) return null
        return Payload.Avatar(version, key, json.optLong("size", 0L))
    }

    private companion object {
        const val KEY_SELF = "avatar:self"

        /** The picture on this phone is newer than the one on the relay, and is still to go. */
        const val KEY_SELF_PENDING = "avatar:self_pending"

        /** For each contact: the picture its owner has now. */
        const val KEY_PEER = "avatar:"

        /** For each contact: the picture this phone already holds. */
        const val KEY_CACHED = "avatar_cache:"
    }
}
