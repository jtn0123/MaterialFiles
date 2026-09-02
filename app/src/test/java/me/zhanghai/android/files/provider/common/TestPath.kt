/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.File
import java.net.URI
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.AccessMode
import java8.nio.file.CopyOption
import java8.nio.file.DirectoryStream
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.LinkOption
import java8.nio.file.OpenOption
import java8.nio.file.Path
import java8.nio.file.PathMatcher
import java8.nio.file.WatchEvent
import java8.nio.file.WatchKey
import java8.nio.file.WatchService
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.attribute.UserPrincipalLookupService
import java8.nio.file.spi.FileSystemProvider

/**
 * A [ByteStringListPath] with '/' as the separator and no real file system behind it, for
 * testing path arithmetic (resolve, normalize, startsWith) without a provider.
 */
class TestPath : ByteStringListPath<TestPath> {
    constructor(path: String) : this(path.toByteString())

    private constructor(path: ByteString) : super(SEPARATOR, path)

    private constructor(absolute: Boolean, segments: List<ByteString>) : super(
        SEPARATOR,
        absolute,
        segments
    )

    override fun isPathAbsolute(path: ByteString): Boolean = !path.isEmpty() && path[0] == SEPARATOR

    override fun createPath(path: ByteString): TestPath = TestPath(path)

    override fun createPath(absolute: Boolean, segments: List<ByteString>): TestPath =
        TestPath(absolute, segments)

    override val defaultDirectory: TestPath
        get() = TestPath("/")

    override fun getFileSystem(): FileSystem = TestFileSystem

    override fun getRoot(): TestPath? = if (isAbsolute) createPath(true, emptyList()) else null

    override fun toRealPath(vararg options: LinkOption): TestPath = this

    override fun toFile(): File = throw UnsupportedOperationException()

    override fun register(
        watcher: WatchService,
        events: Array<WatchEvent.Kind<*>>,
        vararg modifiers: WatchEvent.Modifier
    ): WatchKey = throw UnsupportedOperationException()

    /** Only [getPath] works; it is what [AbstractPath.resolve] needs. */
    private object TestFileSystem : FileSystem() {
        override fun getPath(first: String, vararg more: String): Path =
            TestPath((listOf(first) + more).joinToString(separator))

        override fun getSeparator(): String = SEPARATOR.toInt().toChar().toString()

        override fun provider(): FileSystemProvider = TestFileSystemProvider

        override fun close() = throw UnsupportedOperationException()

        override fun isOpen(): Boolean = true

        override fun isReadOnly(): Boolean = true

        override fun getRootDirectories(): Iterable<Path> = listOf(TestPath("/"))

        override fun getFileStores(): Iterable<FileStore> = emptyList()

        override fun supportedFileAttributeViews(): Set<String> = emptySet()

        override fun getPathMatcher(syntaxAndPattern: String): PathMatcher =
            throw UnsupportedOperationException()

        override fun getUserPrincipalLookupService(): UserPrincipalLookupService =
            throw UnsupportedOperationException()

        override fun newWatchService(): WatchService = throw UnsupportedOperationException()
    }

    /** Exists only so that paths compare as belonging to the same provider. */
    private object TestFileSystemProvider : FileSystemProvider() {
        override fun getScheme(): String = "test"

        override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem = TestFileSystem

        override fun getFileSystem(uri: URI): FileSystem = TestFileSystem

        override fun getPath(uri: URI): Path = TestPath(uri.path)

        override fun newByteChannel(
            path: Path,
            options: Set<OpenOption>,
            vararg attrs: FileAttribute<*>
        ): SeekableByteChannel = throw UnsupportedOperationException()

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

        override fun isHidden(path: Path): Boolean = false

        override fun getFileStore(path: Path): FileStore = throw UnsupportedOperationException()

        override fun checkAccess(path: Path, vararg modes: AccessMode) =
            throw UnsupportedOperationException()

        override fun <V : FileAttributeView> getFileAttributeView(
            path: Path,
            type: Class<V>,
            vararg options: LinkOption
        ): V? = null

        override fun <A : BasicFileAttributes> readAttributes(
            path: Path,
            type: Class<A>,
            vararg options: LinkOption
        ): A = throw UnsupportedOperationException()

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
    }

    companion object {
        private const val SEPARATOR = '/'.code.toByte()
    }
}
