/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import java.io.File
import java.net.URI
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.AccessMode
import java8.nio.file.CopyOption
import java8.nio.file.DirectoryStream
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.OpenOption
import java8.nio.file.Path
import java8.nio.file.PathMatcher
import java8.nio.file.StandardOpenOption
import java8.nio.file.WatchEvent
import java8.nio.file.WatchKey
import java8.nio.file.WatchService
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.attribute.FileTime
import java8.nio.file.attribute.UserPrincipalLookupService
import java8.nio.file.spi.FileSystemProvider
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.ByteStringListPath
import me.zhanghai.android.files.provider.common.toByteString

/**
 * A file system that behaves like a share on a slow server: its paths are neither Linux nor
 * document paths, so the app treats them as remote, and they are read through an
 * [SlowRemoteByteChannel] in positioned chunks, as SMB reads them. The files themselves are
 * ordinary files under [rootDirectory], and what was read from them is counted in [reads].
 */
class SlowRemoteFileSystem(val rootDirectory: File) : FileSystem() {
    val reads = RemoteReads()

    private val provider = SlowRemoteFileSystemProvider(this)

    /** The path of the file [name] directly under [rootDirectory]. */
    fun path(name: String): SlowRemotePath = getPath("/$name")

    override fun getPath(first: String, vararg more: String): SlowRemotePath =
        SlowRemotePath(this, (listOf(first) + more).joinToString(SEPARATOR_STRING).toByteString())

    override fun provider(): FileSystemProvider = provider

    override fun getSeparator(): String = SEPARATOR_STRING

    override fun close() {}

    override fun isOpen(): Boolean = true

    override fun isReadOnly(): Boolean = true

    override fun getRootDirectories(): Iterable<Path> = listOf(getPath(SEPARATOR_STRING))

    override fun getFileStores(): Iterable<FileStore> = emptyList()

    override fun supportedFileAttributeViews(): Set<String> = setOf("basic")

    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher =
        throw UnsupportedOperationException()

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService =
        throw UnsupportedOperationException()

    override fun newWatchService(): WatchService = throw UnsupportedOperationException()

    companion object {
        const val SEPARATOR = '/'.code.toByte()
        const val SEPARATOR_STRING = "/"
    }
}

class SlowRemotePath : ByteStringListPath<SlowRemotePath> {
    private val fileSystem: SlowRemoteFileSystem

    constructor(fileSystem: SlowRemoteFileSystem, path: ByteString) : super(
        SlowRemoteFileSystem.SEPARATOR,
        path
    ) {
        this.fileSystem = fileSystem
    }

    private constructor(
        fileSystem: SlowRemoteFileSystem,
        absolute: Boolean,
        segments: List<ByteString>
    ) : super(SlowRemoteFileSystem.SEPARATOR, absolute, segments) {
        this.fileSystem = fileSystem
    }

    override fun isPathAbsolute(path: ByteString): Boolean =
        path.isNotEmpty() && path[0] == SlowRemoteFileSystem.SEPARATOR

    override fun createPath(path: ByteString): SlowRemotePath = SlowRemotePath(fileSystem, path)

    override fun createPath(absolute: Boolean, segments: List<ByteString>): SlowRemotePath =
        SlowRemotePath(fileSystem, absolute, segments)

    // The root directory is part of the URI, so that the thumbnails of two tests never share an
    // entry on disk.
    override val uriPath: ByteString
        get() = (fileSystem.rootDirectory.path + toAbsolutePath().toString()).toByteString()

    override val defaultDirectory: SlowRemotePath
        get() = fileSystem.getPath(SlowRemoteFileSystem.SEPARATOR_STRING)

    override fun getFileSystem(): SlowRemoteFileSystem = fileSystem

    override fun getRoot(): SlowRemotePath? =
        if (isAbsolute) fileSystem.getPath(SlowRemoteFileSystem.SEPARATOR_STRING) else null

    override fun toRealPath(vararg options: LinkOption): SlowRemotePath = this

    /** The local file behind this path; the app itself never asks a remote path for one. */
    override fun toFile(): File = File(
        fileSystem.rootDirectory,
        toString().removePrefix(SlowRemoteFileSystem.SEPARATOR_STRING)
    )

    override fun register(
        watcher: WatchService,
        events: Array<WatchEvent.Kind<*>>,
        vararg modifiers: WatchEvent.Modifier
    ): WatchKey = throw UnsupportedOperationException()
}

private class SlowRemoteFileSystemProvider(private val fileSystem: SlowRemoteFileSystem) :
    FileSystemProvider() {
    override fun getScheme(): String = "slow-remote"

    override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem =
        throw UnsupportedOperationException()

    override fun getFileSystem(uri: URI): FileSystem = fileSystem

    override fun getPath(uri: URI): Path = throw UnsupportedOperationException()

    // newInputStream() is left to FileSystemProvider, which reads through this channel, as it does
    // for SMB.
    override fun newByteChannel(
        path: Path,
        options: Set<OpenOption>,
        vararg attrs: FileAttribute<*>
    ): SeekableByteChannel {
        if (options.any { it != StandardOpenOption.READ && it != LinkOption.NOFOLLOW_LINKS }) {
            throw UnsupportedOperationException(options.toString())
        }
        val file = path.toExistingFile()
        return SlowRemoteByteChannel(file, fileSystem.reads)
    }

    override fun newDirectoryStream(
        dir: Path,
        filter: DirectoryStream.Filter<in Path>
    ): DirectoryStream<Path> = throw UnsupportedOperationException()

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) =
        throw UnsupportedOperationException()

    override fun delete(path: Path) = throw UnsupportedOperationException()

    override fun copy(source: Path, target: Path, vararg options: CopyOption) =
        throw UnsupportedOperationException()

    override fun move(source: Path, target: Path, vararg options: CopyOption) =
        throw UnsupportedOperationException()

    override fun isSameFile(path: Path, path2: Path): Boolean = path == path2

    override fun isHidden(path: Path): Boolean = path.fileName.toString().startsWith(".")

    override fun getFileStore(path: Path): FileStore = throw UnsupportedOperationException()

    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        path.toExistingFile()
    }

    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption
    ): V? = null

    @Suppress("UNCHECKED_CAST")
    override fun <A : BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption
    ): A = if (type == BasicFileAttributes::class.java) {
        FileAttributes(path.toExistingFile()) as A
    } else {
        throw UnsupportedOperationException(type.toString())
    }

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption
    ): Map<String, Any> = throw UnsupportedOperationException()

    override fun setAttribute(
        path: Path,
        attribute: String,
        value: Any,
        vararg options: LinkOption
    ) = throw UnsupportedOperationException()

    private fun Path.toExistingFile(): File {
        val file = (this as SlowRemotePath).toFile()
        if (!file.exists()) {
            throw NoSuchFileException(toString())
        }
        return file
    }
}

private class FileAttributes(file: File) : BasicFileAttributes {
    private val lastModifiedTime = FileTime.fromMillis(file.lastModified())
    private val isDirectory = file.isDirectory
    private val size = file.length()
    private val fileKey = file.path

    override fun lastModifiedTime(): FileTime = lastModifiedTime

    override fun lastAccessTime(): FileTime = lastModifiedTime

    override fun creationTime(): FileTime = lastModifiedTime

    override fun isRegularFile(): Boolean = !isDirectory

    override fun isDirectory(): Boolean = isDirectory

    override fun isSymbolicLink(): Boolean = false

    override fun isOther(): Boolean = false

    override fun size(): Long = size

    override fun fileKey(): Any = fileKey
}
