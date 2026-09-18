package me.zhanghai.android.files.viewer.text

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal data class TextDraft(val text: String, val encoding: String, val start: Int, val end: Int)

/** Private, excluded-from-backup recovery data. Large text never enters an Android Bundle. */
internal class TextDraftStore(directory: File, identity: String) {
    private val file = File(
        directory,
        MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    )

    private val lock = locks[(file.absolutePath.hashCode() and Int.MAX_VALUE) % locks.size]
    private var revision: String? = null

    fun read(): TextDraft? = synchronized(lock) {
        if (!file.exists()) {
            revision = null
            return@synchronized null
        }
        DataInputStream(file.inputStream().buffered()).use {
            revision = readRevision(it)
            val encoding = it.readUTF()
            val start = it.readInt()
            val end = it.readInt()
            val size = it.readInt()
            require(size in 0..MAX_BYTES)
            val bytes = ByteArray(size)
            it.readFully(bytes)
            TextDraft(String(bytes, Charsets.UTF_8), encoding, start, end)
        }
    }

    fun write(draft: TextDraft) = synchronized(lock) {
        checkRevision()
        val nextRevision = UUID.randomUUID().toString()
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val temporary = File.createTempFile("draft-", ".tmp", file.parentFile)
        try {
            FileOutputStream(temporary).use { output ->
                val data = DataOutputStream(output)
                val bytes = draft.text.toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_BYTES)
                data.writeInt(2)
                data.writeUTF(nextRevision)
                data.writeUTF(draft.encoding)
                data.writeInt(draft.start)
                data.writeInt(draft.end)
                data.writeInt(bytes.size)
                data.write(bytes)
                data.flush()
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            revision = nextRevision
        } finally {
            try {
                Files.deleteIfExists(temporary.toPath())
            } catch (e: java.io.IOException) {
                // Cleanup must not hide a write failure or invalidate a committed draft.
                e.printStackTrace()
            }
        }
    }

    fun clear() = synchronized(lock) {
        checkRevision()
        Files.deleteIfExists(file.toPath())
        revision = null
    }

    private fun checkRevision() {
        val current = if (file.exists()) {
            DataInputStream(file.inputStream().buffered()).use { readRevision(it) }
        } else {
            null
        }
        if (current != revision) {
            throw IOException(
                "A newer editor changed this recovery draft. Reopen the file to recover it."
            )
        }
    }

    private fun readRevision(input: DataInputStream): String = when (input.readInt()) {
        1 -> "legacy"
        2 -> input.readUTF()
        else -> throw IOException("Unknown draft version")
    }

    companion object {
        private val locks = Array(64) { Any() }
        private const val MAX_BYTES = 16 * 1024 * 1024
    }
}
