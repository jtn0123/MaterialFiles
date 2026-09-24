/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import android.os.Parcel
import android.os.Parcelable
import java.io.IOException
import java.io.InputStream
import java8.nio.file.ClosedFileSystemException
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.NoSuchFileException
import java8.nio.file.NotDirectoryException
import java8.nio.file.NotLinkException
import java8.nio.file.Path
import java8.nio.file.PathMatcher
import java8.nio.file.WatchService
import java8.nio.file.attribute.UserPrincipalLookupService
import java8.nio.file.spi.FileSystemProvider
import me.zhanghai.android.files.provider.archive.archiver.ArchiveReader
import me.zhanghai.android.files.provider.archive.archiver.ReadArchive
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.ByteStringBuilder
import me.zhanghai.android.files.provider.common.ByteStringListPathCreator
import me.zhanghai.android.files.provider.common.DelegateInputStream
import me.zhanghai.android.files.provider.common.IsDirectoryException
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.util.closeSafe
import me.zhanghai.android.libarchive.ArchiveException

internal class ArchiveFileSystem(
    private val provider: ArchiveFileSystemProvider,
    val archiveFile: Path
) : FileSystem(),
    ByteStringListPathCreator,
    Parcelable {
    val rootDirectory = ArchivePath(this, SEPARATOR_BYTE_STRING)

    init {
        if (!rootDirectory.isAbsolute) {
            throw AssertionError("Root directory $rootDirectory must be absolute")
        }
        if (rootDirectory.nameCount != 0) {
            throw AssertionError("Root directory $rootDirectory must contain no names")
        }
    }

    val defaultDirectory: ArchivePath
        get() = rootDirectory

    private val lock = Any()

    private var isOpen = true

    private var passwords = listOf<String>()

    private var isRefreshNeeded = true

    private var entries: ArchiveReader.Entries? = null

    // One forward-reading archive kept between newInputStream() calls, so that extracting entries
    // in archive order reads the archive once instead of once per entry.
    private var cachedArchive: ArchiveReader.OpenArchive? = null

    private var isCachedArchiveInUse = false

    private var isCachedArchiveStale = false

    @Throws(IOException::class)
    fun getEntry(path: Path): ReadArchive.Entry = synchronized(lock) {
        ensureEntriesLocked(path).getEntry(path)
    }

    @Throws(NoSuchFileException::class)
    private fun ArchiveReader.Entries.getEntry(path: Path): ReadArchive.Entry =
        entries[path] ?: throw NoSuchFileException(path.toString())

    @Throws(IOException::class)
    fun newInputStream(file: Path): InputStream = synchronized(lock) {
        val entries = ensureEntriesLocked(file)
        val entry = entries.getEntry(file)
        if (entry.isDirectory) {
            throw IsDirectoryException(file.toString())
        }
        val index = entries.indices[entry] ?: throw NoSuchFileException(file.toString())
        val archive = openArchiveBeforeLocked(index, file)
        val found = try {
            archive.seekTo(index, entry.name)
        } catch (e: ArchiveException) {
            closeArchiveLocked(archive)
            throw e.toFileSystemOrInterruptedIOException(file)
        }
        if (!found) {
            closeArchiveLocked(archive)
            throw NoSuchFileException(file.toString())
        }
        val isCaching = cachedArchive == null || archive === cachedArchive
        if (isCaching) {
            cachedArchive = archive
            isCachedArchiveInUse = true
        }
        val inputStream = try {
            archive.newDataInputStream()
        } catch (e: ArchiveException) {
            releaseArchiveLocked(archive, isCaching)
            throw e.toFileSystemOrInterruptedIOException(file)
        }
        ArchiveExceptionInputStream(
            object : DelegateInputStream(inputStream) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        synchronized(lock) { releaseArchiveLocked(archive, isCaching) }
                    }
                }
            },
            file
        )
    }

    /**
     * Returns the cached archive if it has not read past the entry at [index] yet, or opens a new
     * one. A cached archive that cannot be reused is closed here unless a stream still reads it.
     */
    @Throws(IOException::class)
    private fun openArchiveBeforeLocked(index: Int, file: Path): ArchiveReader.OpenArchive {
        val cached = cachedArchive
        if (cached != null && !isCachedArchiveInUse && !isCachedArchiveStale &&
            cached.position < index
        ) {
            return cached
        }
        if (!isCachedArchiveInUse) {
            cached?.closeSafe()
            cachedArchive = null
            isCachedArchiveStale = false
        }
        return try {
            ArchiveReader.open(archiveFile, passwords)
        } catch (e: ArchiveException) {
            throw e.toFileSystemOrInterruptedIOException(file)
        }
    }

    private fun closeArchiveLocked(archive: ArchiveReader.OpenArchive) {
        archive.closeSafe()
        if (archive === cachedArchive) {
            cachedArchive = null
        }
    }

    private fun releaseArchiveLocked(archive: ArchiveReader.OpenArchive, isCached: Boolean) {
        if (isCached && archive === cachedArchive) {
            isCachedArchiveInUse = false
            if (!isOpen || isCachedArchiveStale) {
                cachedArchive = null
                isCachedArchiveStale = false
                archive.closeSafe()
            }
        } else {
            archive.closeSafe()
        }
    }

    private fun discardCachedArchiveLocked() {
        if (isCachedArchiveInUse) {
            // The stream holding it closes it, see releaseArchiveLocked().
            isCachedArchiveStale = true
        } else {
            cachedArchive?.closeSafe()
            cachedArchive = null
            isCachedArchiveStale = false
        }
    }

    @Throws(IOException::class)
    fun getDirectoryChildren(directory: Path): List<Path> = synchronized(lock) {
        val entries = ensureEntriesLocked(directory)
        val entry = entries.getEntry(directory)
        if (!entry.isDirectory) {
            throw NotDirectoryException(directory.toString())
        }
        // ArchiveReader.readEntries() gives every directory entry a list, even an empty one.
        checkNotNull(entries.tree[directory]) { "No children listed for directory $directory" }
    }

    @Throws(IOException::class)
    fun readSymbolicLink(link: Path): String = synchronized(lock) {
        val entry = ensureEntriesLocked(link).getEntry(link)
        if (!entry.isSymbolicLink) {
            throw NotLinkException(link.toString())
        }
        entry.symbolicLinkTarget.orEmpty()
    }

    fun addPassword(password: String) {
        synchronized(lock) {
            if (!isOpen) {
                throw ClosedFileSystemException()
            }
            passwords += password
            discardCachedArchiveLocked()
        }
    }

    fun refresh() {
        synchronized(lock) {
            if (!isOpen) {
                throw ClosedFileSystemException()
            }
            isRefreshNeeded = true
            discardCachedArchiveLocked()
        }
    }

    @Throws(IOException::class)
    private fun ensureEntriesLocked(file: Path): ArchiveReader.Entries {
        if (!isOpen) {
            throw ClosedFileSystemException()
        }
        val entries = entries
        if (entries != null && !isRefreshNeeded) {
            return entries
        }
        val readEntries = try {
            ArchiveReader.readEntries(archiveFile, passwords, rootDirectory)
        } catch (e: ArchiveException) {
            throw e.toFileSystemOrInterruptedIOException(file)
        }
        this.entries = readEntries
        isRefreshNeeded = false
        return readEntries
    }

    override fun provider(): FileSystemProvider = provider

    override fun close() {
        synchronized(lock) {
            if (!isOpen) {
                return
            }
            provider.removeFileSystem(this)
            isRefreshNeeded = false
            entries = null
            isOpen = false
            discardCachedArchiveLocked()
        }
    }

    override fun isOpen(): Boolean = synchronized(lock) { isOpen }

    override fun isReadOnly(): Boolean = true

    override fun getSeparator(): String = SEPARATOR_STRING

    override fun getRootDirectories(): Iterable<Path> = listOf(rootDirectory)

    override fun getFileStores(): Iterable<FileStore> {
        // TODO
        throw UnsupportedOperationException()
    }

    override fun supportedFileAttributeViews(): Set<String> =
        ArchiveFileAttributeView.SUPPORTED_NAMES

    override fun getPath(first: String, vararg more: String): ArchivePath {
        val path = ByteStringBuilder(first.toByteString())
            .apply { more.forEach { append(SEPARATOR).append(it.toByteString()) } }
            .toByteString()
        return ArchivePath(this, path)
    }

    override fun getPath(first: ByteString, vararg more: ByteString): ArchivePath {
        val path = ByteStringBuilder(first)
            .apply { more.forEach { append(SEPARATOR).append(it) } }
            .toByteString()
        return ArchivePath(this, path)
    }

    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher =
        throw UnsupportedOperationException()

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService =
        throw UnsupportedOperationException()

    @Throws(IOException::class)
    override fun newWatchService(): WatchService {
        // TODO
        throw UnsupportedOperationException()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (javaClass != other?.javaClass) {
            return false
        }
        other as ArchiveFileSystem
        return archiveFile == other.archiveFile
    }

    override fun hashCode(): Int = archiveFile.hashCode()

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeParcelable(archiveFile as Parcelable, flags)
    }

    companion object {
        const val SEPARATOR = '/'.code.toByte()
        private val SEPARATOR_BYTE_STRING = SEPARATOR.toByteString()
        private const val SEPARATOR_STRING = SEPARATOR.toInt().toChar().toString()

        @JvmField
        val CREATOR = object : Parcelable.Creator<ArchiveFileSystem> {
            override fun createFromParcel(source: Parcel): ArchiveFileSystem {
                val archiveFile = checkNotNull(
                    source.readParcelable(Path::class.java.classLoader, Parcelable::class.java)
                ) as Path
                return ArchiveFileSystemProvider.getOrNewFileSystem(archiveFile)
            }

            override fun newArray(size: Int): Array<ArchiveFileSystem?> = arrayOfNulls(size)
        }
    }
}
