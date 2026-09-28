/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
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
import java8.nio.file.WatchEvent
import java8.nio.file.WatchKey
import java8.nio.file.WatchService
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.attribute.PosixFileAttributeView
import java8.nio.file.attribute.UserPrincipalLookupService
import java8.nio.file.spi.FileSystemProvider

/**
 * A file system on a server that has stopped answering: every read, write and listing fails with
 * an [IOException]. Only the access check still answers, from [existingPaths], so that callers
 * can be led past their own "may I write here" checks to the operation that then fails.
 */
class FailingFileSystem(private val existingPaths: Set<String> = emptySet()) : FileSystem() {
    override fun getPath(first: String, vararg more: String): FailingPath =
        FailingPath(this, (listOf(first) + more).joinToString(SEPARATOR_STRING).toByteString())

    override fun provider(): FileSystemProvider = FailingFileSystemProvider

    override fun getSeparator(): String = SEPARATOR_STRING

    override fun close() {}

    override fun isOpen(): Boolean = true

    override fun isReadOnly(): Boolean = false

    override fun getRootDirectories(): Iterable<Path> = listOf(getPath(SEPARATOR_STRING))

    override fun getFileStores(): Iterable<FileStore> = emptyList()

    override fun supportedFileAttributeViews(): Set<String> = setOf("basic", "posix")

    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher =
        throw UnsupportedOperationException()

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService =
        throw UnsupportedOperationException()

    override fun newWatchService(): WatchService = throw UnsupportedOperationException()

    internal fun exists(path: Path): Boolean = path.toString() in existingPaths

    companion object {
        private const val SEPARATOR = '/'.code.toByte()
        private const val SEPARATOR_STRING = "/"
    }

    class FailingPath : ByteStringListPath<FailingPath> {
        private val fileSystem: FailingFileSystem

        internal constructor(fileSystem: FailingFileSystem, path: ByteString) : super(
            SEPARATOR,
            path
        ) {
            this.fileSystem = fileSystem
        }

        private constructor(
            fileSystem: FailingFileSystem,
            absolute: Boolean,
            segments: List<ByteString>
        ) : super(SEPARATOR, absolute, segments) {
            this.fileSystem = fileSystem
        }

        override fun isPathAbsolute(path: ByteString): Boolean =
            !path.isEmpty() && path[0] == SEPARATOR

        override fun createPath(path: ByteString): FailingPath = FailingPath(fileSystem, path)

        override fun createPath(absolute: Boolean, segments: List<ByteString>): FailingPath =
            FailingPath(fileSystem, absolute, segments)

        override val defaultDirectory: FailingPath
            get() = fileSystem.getPath(SEPARATOR_STRING)

        override fun getFileSystem(): FailingFileSystem = fileSystem

        override fun getRoot(): FailingPath? =
            if (isAbsolute) createPath(true, emptyList()) else null

        override fun toRealPath(vararg options: LinkOption): FailingPath = this

        override fun toFile(): File = throw UnsupportedOperationException()

        override fun register(
            watcher: WatchService,
            events: Array<WatchEvent.Kind<*>>,
            vararg modifiers: WatchEvent.Modifier
        ): WatchKey = throw UnsupportedOperationException()
    }
}

private fun connectionReset(): Nothing = throw IOException("Connection reset")

private object FailingFileSystemProvider : FileSystemProvider() {
    override fun getScheme(): String = "failing"

    override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem =
        throw UnsupportedOperationException()

    override fun getFileSystem(uri: URI): FileSystem = throw UnsupportedOperationException()

    override fun getPath(uri: URI): Path = throw UnsupportedOperationException()

    override fun newByteChannel(
        path: Path,
        options: Set<OpenOption>,
        vararg attrs: FileAttribute<*>
    ): SeekableByteChannel = connectionReset()

    override fun newDirectoryStream(
        dir: Path,
        filter: DirectoryStream.Filter<in Path>
    ): DirectoryStream<Path> = connectionReset()

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) = connectionReset()

    override fun delete(path: Path) = connectionReset()

    override fun copy(source: Path, target: Path, vararg options: CopyOption) = connectionReset()

    override fun move(source: Path, target: Path, vararg options: CopyOption) = connectionReset()

    override fun isSameFile(path: Path, path2: Path): Boolean = path == path2

    override fun isHidden(path: Path): Boolean = false

    override fun getFileStore(path: Path): FileStore = connectionReset()

    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        if (!(path.fileSystem as FailingFileSystem).exists(path)) {
            throw NoSuchFileException(path.toString())
        }
    }

    /** A view whose every read and write fails, standing in for any kind asked for. */
    @Suppress("UNCHECKED_CAST")
    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption
    ): V = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(PosixFileAttributeView::class.java),
        InvocationHandler { _, method, _ ->
            if (method.name == "name") "posix" else connectionReset()
        }
    ) as V

    override fun <A : BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption
    ): A = connectionReset()

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption
    ): Map<String, Any> = connectionReset()

    override fun setAttribute(
        path: Path,
        attribute: String,
        value: Any,
        vararg options: LinkOption
    ) = connectionReset()
}
