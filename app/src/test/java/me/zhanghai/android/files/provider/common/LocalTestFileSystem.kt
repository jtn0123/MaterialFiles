/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path as JavaPath
import java.nio.file.StandardOpenOption
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.AccessMode
import java8.nio.file.CopyOption
import java8.nio.file.DirectoryStream
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.OpenOption
import java8.nio.file.Path
import java8.nio.file.PathMatcher
import java8.nio.file.StandardCopyOption
import java8.nio.file.WatchService
import java8.nio.file.attribute.BasicFileAttributeView
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.attribute.FileTime
import java8.nio.file.attribute.UserPrincipalLookupService

/**
 * A second, real provider for tests: a java8 file system whose paths are served by the JVM's own
 * `java.nio.file` implementation under a directory. [ForeignCopyMove] and anything else written
 * against the provider-neutral [Path] API can be driven between this and, say, the FTP provider
 * without an Android device.
 */
internal class LocalTestFileSystem(val rootDirectory: JavaPath) : FileSystem() {
    /** Directories whose listing breaks off after their first child, as a flaky server's can. */
    val brokenListings = mutableSetOf<Path>()

    override fun getPath(first: String, vararg more: String): LocalTestPath =
        LocalTestPath(this, (listOf(first) + more).joinToString(SEPARATOR_STRING).toByteString())

    override fun provider(): LocalTestFileSystemProvider = LocalTestFileSystemProvider

    override fun getSeparator(): String = SEPARATOR_STRING

    override fun close() {}

    override fun isOpen(): Boolean = true

    override fun isReadOnly(): Boolean = false

    override fun getRootDirectories(): Iterable<Path> = listOf(getPath("/"))

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

/** A path of a [LocalTestFileSystem], which maps onto a file below its root directory. */
internal class LocalTestPath : ByteStringListPath<LocalTestPath> {
    private val fileSystem: LocalTestFileSystem

    constructor(
        fileSystem: LocalTestFileSystem,
        path: ByteString
    ) : super(LocalTestFileSystem.SEPARATOR, path) {
        this.fileSystem = fileSystem
    }

    private constructor(
        fileSystem: LocalTestFileSystem,
        absolute: Boolean,
        segments: List<ByteString>
    ) : super(LocalTestFileSystem.SEPARATOR, absolute, segments) {
        this.fileSystem = fileSystem
    }

    override fun isPathAbsolute(path: ByteString): Boolean =
        path.isNotEmpty() && path[0] == LocalTestFileSystem.SEPARATOR

    override fun createPath(path: ByteString): LocalTestPath = LocalTestPath(fileSystem, path)

    override fun createPath(absolute: Boolean, segments: List<ByteString>): LocalTestPath =
        LocalTestPath(fileSystem, absolute, segments)

    override val uriScheme: String
        get() = LocalTestFileSystemProvider.SCHEME

    override val defaultDirectory: LocalTestPath
        get() = fileSystem.getPath("/")

    override fun getFileSystem(): LocalTestFileSystem = fileSystem

    override fun getRoot(): LocalTestPath? = if (isAbsolute) fileSystem.getPath("/") else null

    override fun toRealPath(vararg options: LinkOption): LocalTestPath = this

    override fun toFile(): File = toJavaPath().toFile()

    override fun register(
        watcher: WatchService,
        events: Array<java8.nio.file.WatchEvent.Kind<*>>,
        vararg modifiers: java8.nio.file.WatchEvent.Modifier
    ): java8.nio.file.WatchKey = throw UnsupportedOperationException()

    /** The file this path stands for, below the file system's root directory. */
    fun toJavaPath(): JavaPath = fileSystem.rootDirectory.resolve(
        toString().removePrefix(LocalTestFileSystem.SEPARATOR_STRING)
    )
}
