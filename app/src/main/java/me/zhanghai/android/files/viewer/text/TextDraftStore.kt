package me.zhanghai.android.files.viewer.text

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class TextDraft(val text: String, val encoding: String, val start: Int, val end: Int)

/** Private, excluded-from-backup recovery data. Large text never enters an Android Bundle. */
internal class TextDraftStore(directory: File, identity: String) {
    private val file = File(
        directory,
        MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    )

    fun read(): TextDraft? {
        if (!file.exists()) return null
        return DataInputStream(file.inputStream().buffered()).use {
            check(it.readInt() == 1) { "Unknown draft version" }
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

    fun write(draft: TextDraft) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val temporary = File.createTempFile("draft-", ".tmp", file.parentFile)
        try {
            FileOutputStream(temporary).use { output ->
                val data = DataOutputStream(output)
                val bytes = draft.text.toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_BYTES)
                data.writeInt(1)
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
        } finally {
            temporary.delete()
        }
    }

    fun clear() {
        Files.deleteIfExists(file.toPath())
    }

    companion object {
        private const val MAX_BYTES = 16 * 1024 * 1024
    }
}
