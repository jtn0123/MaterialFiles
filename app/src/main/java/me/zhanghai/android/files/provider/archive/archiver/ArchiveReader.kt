/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import androidx.preference.PreferenceManager
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.IdentityHashMap
import java8.nio.channels.SeekableByteChannel
import java8.nio.charset.StandardCharsets
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.DelegateForceableSeekableByteChannel
import me.zhanghai.android.files.provider.common.DelegateInputStream
import me.zhanghai.android.files.provider.common.DelegateNonForceableSeekableByteChannel
import me.zhanghai.android.files.provider.common.ForceableChannel
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.root.isRunningAsRoot
import me.zhanghai.android.files.provider.root.rootContext
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

object ArchiveReader {
    @Throws(IOException::class)
    fun readEntries(file: Path, passwords: List<String>, rootPath: Path): Entries {
        val entries = mutableMapOf<Path, ReadArchive.Entry>()
        val rawEntries = readEntries(file, passwords)
        val indices = IdentityHashMap<ReadArchive.Entry, Int>()
        for ((index, entry) in rawEntries.withIndex()) {
            indices[entry] = index
            val path = normalizeEntryPath(rootPath, entry.name, entry.isDirectory) ?: continue
            entries.getOrPut(path) { entry }
        }
        entries.getOrPut(rootPath) { createDirectoryEntry("") }
        val tree = mutableMapOf<Path, MutableList<Path>>()
        tree[rootPath] = mutableListOf()
        val paths = entries.keys.toList()
        for (path in paths) {
            var path = path
            while (true) {
                val parentPath = path.parent ?: break
                val entry = entries[path]!!
                if (entry.isDirectory) {
                    tree.getOrPut(path) { mutableListOf() }
                }
                tree.getOrPut(parentPath) { mutableListOf() }.add(path)
                if (entries.containsKey(parentPath)) {
                    break
                }
                entries[parentPath] = createDirectoryEntry(parentPath.toString())
                path = parentPath
            }
        }
        return Entries(entries, tree, indices)
    }

    /**
     * @param indices the position of each entry in the archive's read order, for
     * [OpenArchive.seekTo]; synthesized directory entries have none.
     */
    class Entries(
        val entries: Map<Path, ReadArchive.Entry>,
        val tree: Map<Path, List<Path>>,
        val indices: Map<ReadArchive.Entry, Int>
    )

    /**
     * Resolves an archive entry name under [rootPath] so that it can never escape the archive,
     * or returns null when the entry should be ignored.
     */
    internal fun normalizeEntryPath(
        rootPath: Path,
        entryName: String,
        isDirectory: Boolean
    ): Path? {
        var path = rootPath.resolve(entryName)
        // Normalize an absolute path to prevent path traversal attack.
        if (!path.isAbsolute) {
            // TODO: Will this actually happen?
            throw AssertionError("Path must be absolute: $path")
        }
        if (path.nameCount > 0) {
            path = path.normalize()
            if (path.nameCount == 0) {
                // Don't allow a path to become the root path only after normalization.
                return null
            }
        } else {
            if (!isDirectory) {
                // Ignore a root path that's not a directory
                return null
            }
        }
        return path
    }

    private fun createDirectoryEntry(name: String): ReadArchive.Entry {
        require(!name.endsWith("/")) { "name $name should not end with a slash" }
        return ReadArchive.Entry(
            name, false, null, null, null, PosixFileType.DIRECTORY, 0, null, null,
            PosixFileMode.DIRECTORY_DEFAULT, null
        )
    }

    @Throws(IOException::class)
    private fun readEntries(file: Path, passwords: List<String>): List<ReadArchive.Entry> {
        val charset = archiveFileNameCharset
        val (archive, closeable) = openArchive(file, passwords)
        return closeable.use {
            buildList {
                while (true) {
                    this += archive.readEntry(charset) ?: break
                }
            }
        }
    }

    /**
     * An archive positioned for reading entry data. libarchive only reads forward, so extracting
     * several entries reuses one [OpenArchive] as long as each is at a later position than the
     * last; see [ArchiveFileSystem].
     */
    class OpenArchive internal constructor(
        private val archive: ReadArchive,
        private val closeable: Closeable,
        private val charset: Charset
    ) : Closeable {
        /** Read-order index of the entry whose header was read last, or -1 before the first. */
        var position = -1
            private set

        private var currentName: String? = null

        /**
         * Reads headers forward until the entry at [index], and returns whether it is there and
         * named [name].
         */
        @Throws(IOException::class)
        fun seekTo(index: Int, name: String): Boolean {
            require(index > position) { "Cannot seek backwards from $position to $index" }
            while (position < index) {
                val entry = archive.readEntry(charset) ?: return false
                ++position
                currentName = entry.name
            }
            return currentName == name
        }

        @Throws(IOException::class)
        fun newDataInputStream(): InputStream = archive.newDataInputStream()

        @Throws(IOException::class)
        override fun close() {
            closeable.close()
        }
    }

    @Throws(IOException::class)
    fun open(file: Path, passwords: List<String>): OpenArchive {
        val (archive, closeable) = openArchive(file, passwords)
        return OpenArchive(archive, closeable, archiveFileNameCharset)
    }

    @Throws(IOException::class)
    private fun openArchive(
        file: Path,
        passwords: List<String>
    ): Pair<ReadArchive, ArchiveCloseable> {
        val channel = try {
            CacheSizeSeekableByteChannel(file.newByteChannel())
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        if (channel != null) {
            var successful = false
            try {
                val archive = ReadArchive(channel, passwords)
                successful = true
                return archive to ArchiveCloseable(archive, channel)
            } finally {
                if (!successful) {
                    channel.close()
                }
            }
        }
        val inputStream = file.newInputStream()
        var successful = false
        try {
            val archive = ReadArchive(inputStream, passwords)
            successful = true
            return archive to ArchiveCloseable(archive, inputStream)
        } finally {
            if (!successful) {
                inputStream.close()
            }
        }
    }

    // size() may be called repeatedly for ZIP and 7Z, so make it cached to improve performance.
    private fun CacheSizeSeekableByteChannel(channel: SeekableByteChannel): SeekableByteChannel =
        if (channel is ForceableChannel) {
            CacheSizeForceableSeekableByteChannel(channel)
        } else {
            CacheSizeNonForceableSeekableByteChannel(channel)
        }

    private class CacheSizeNonForceableSeekableByteChannel(channel: SeekableByteChannel) :
        DelegateNonForceableSeekableByteChannel(channel) {
        private val size: Long by lazy { super.size() }

        override fun size(): Long = size
    }

    private class CacheSizeForceableSeekableByteChannel(channel: SeekableByteChannel) :
        DelegateForceableSeekableByteChannel(channel) {
        private val size: Long by lazy { super.size() }

        override fun size(): Long = size
    }

    private val archiveFileNameCharset: Charset
        get() =
            if (isRunningAsRoot) {
                try {
                    val sharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(rootContext)
                    val key = rootContext.getString(R.string.pref_key_archive_file_name_encoding)
                    val defaultValue = rootContext.getString(
                        R.string.pref_default_value_archive_file_name_encoding
                    )
                    Charset.forName(sharedPreferences.getString(key, defaultValue)!!)
                } catch (e: Exception) {
                    e.printStackTrace()
                    StandardCharsets.UTF_8
                }
            } else {
                Charset.forName(Settings.ARCHIVE_FILE_NAME_ENCODING.valueCompat)
            }

    private class ArchiveCloseable(
        private val archive: ReadArchive,
        private val closeable: Closeable
    ) : Closeable {
        override fun close() {
            @Suppress("ConvertTryFinallyToUseCall")
            try {
                archive.close()
            } finally {
                closeable.close()
            }
        }
    }
}
