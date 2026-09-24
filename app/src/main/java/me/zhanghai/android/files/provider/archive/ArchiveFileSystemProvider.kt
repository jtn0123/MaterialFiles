/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java8.nio.channels.FileChannel
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.AccessDeniedException
import java8.nio.file.AccessMode
import java8.nio.file.CopyOption
import java8.nio.file.DirectoryStream
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.LinkOption
import java8.nio.file.OpenOption
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.ProviderMismatchException
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.spi.FileSystemProvider
import me.zhanghai.android.files.provider.common.ByteStringPath
import me.zhanghai.android.files.provider.common.FileSystemCache
import me.zhanghai.android.files.provider.common.PathListDirectoryStream
import me.zhanghai.android.files.provider.common.PathObservable
import me.zhanghai.android.files.provider.common.PathObservableProvider
import me.zhanghai.android.files.provider.common.ReadOnlyFileSystemException
import me.zhanghai.android.files.provider.common.Searchable
import me.zhanghai.android.files.provider.common.WalkFileTreeSearchable
import me.zhanghai.android.files.provider.common.decodedPathByteString
import me.zhanghai.android.files.provider.common.decodedQueryByteString
import me.zhanghai.android.files.provider.common.isSameFile
import me.zhanghai.android.files.provider.common.requireProviderPath
import me.zhanghai.android.files.provider.common.toAccessModes
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.common.toOpenOptions

object ArchiveFileSystemProvider : FileSystemProvider(), PathObservableProvider, Searchable {
    private const val SCHEME = "archive"

    private val fileSystems = FileSystemCache<Path, ArchiveFileSystem>()

    override fun getScheme(): String = SCHEME

    override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem {
        uri.requireSameScheme()
        val archiveFile = uri.archiveFile
        return fileSystems.create(archiveFile) { newFileSystem(archiveFile) }
    }

    override fun newFileSystem(file: Path, env: Map<String, *>): FileSystem = newFileSystem(file)

    internal fun getOrNewFileSystem(archiveFile: Path): ArchiveFileSystem =
        fileSystems.getOrCreate(archiveFile) { newFileSystem(archiveFile) }

    private fun newFileSystem(archiveFile: Path): ArchiveFileSystem =
        ArchiveFileSystem(this, archiveFile)

    override fun getFileSystem(uri: URI): FileSystem {
        uri.requireSameScheme()
        val archiveFile = uri.archiveFile
        return fileSystems[archiveFile]
    }

    internal fun removeFileSystem(fileSystem: ArchiveFileSystem) {
        fileSystems.remove(fileSystem.archiveFile, fileSystem)
    }

    override fun getPath(uri: URI): Path {
        uri.requireSameScheme()
        val archiveFile = uri.archiveFile
        val path = uri.decodedQueryByteString
            ?: throw IllegalArgumentException("URI must have a query")
        return getOrNewFileSystem(archiveFile).getPath(path)
    }

    private fun URI.requireSameScheme() {
        val scheme = scheme
        require(scheme == SCHEME) { "URI scheme $scheme must be $SCHEME" }
    }

    private val URI.archiveFile: Path
        get() {
            val path = decodedPathByteString
                ?: throw IllegalArgumentException("URI must have a path")
            // Drop the first character which is always a slash.
            val archiveUri = URI.create(path.toString().drop(1))
            return Paths.get(archiveUri)
        }

    @Throws(IOException::class)
    override fun newInputStream(file: Path, vararg options: OpenOption): InputStream {
        requireProviderPath<ArchivePath>(file)
        options.toOpenOptions().checkForArchive()
        return file.fileSystem.newInputStream(file)
    }

    override fun newFileChannel(
        file: Path,
        options: Set<OpenOption>,
        vararg attributes: FileAttribute<*>
    ): FileChannel {
        requireProviderPath<ArchivePath>(file)
        options.toOpenOptions().checkForArchive()
        if (attributes.isNotEmpty()) {
            throw UnsupportedOperationException(attributes.contentToString())
        }
        throw UnsupportedOperationException()
    }

    override fun newByteChannel(
        file: Path,
        options: Set<OpenOption>,
        vararg attributes: FileAttribute<*>
    ): SeekableByteChannel {
        requireProviderPath<ArchivePath>(file)
        options.toOpenOptions().checkForArchive()
        if (attributes.isNotEmpty()) {
            throw UnsupportedOperationException(attributes.contentToString())
        }
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun newDirectoryStream(
        directory: Path,
        filter: DirectoryStream.Filter<in Path>
    ): DirectoryStream<Path> {
        requireProviderPath<ArchivePath>(directory)
        val children = directory.fileSystem.getDirectoryChildren(directory)
        return PathListDirectoryStream(children, filter)
    }

    @Throws(IOException::class)
    override fun createDirectory(directory: Path, vararg attributes: FileAttribute<*>) {
        requireProviderPath<ArchivePath>(directory)
        throw ReadOnlyFileSystemException(directory.toString())
    }

    @Throws(IOException::class)
    override fun createSymbolicLink(link: Path, target: Path, vararg attributes: FileAttribute<*>) {
        requireProviderPath<ArchivePath>(link)
        if (target !is ArchivePath && target !is ByteStringPath) {
            throw ProviderMismatchException(target.toString())
        }
        throw ReadOnlyFileSystemException(link.toString(), target.toString(), null)
    }

    @Throws(IOException::class)
    override fun createLink(link: Path, existing: Path) {
        requireProviderPath<ArchivePath>(link)
        requireProviderPath<ArchivePath>(existing)
        throw ReadOnlyFileSystemException(link.toString(), existing.toString(), null)
    }

    @Throws(IOException::class)
    override fun delete(path: Path) {
        requireProviderPath<ArchivePath>(path)
        throw ReadOnlyFileSystemException(path.toString())
    }

    @Throws(IOException::class)
    override fun readSymbolicLink(link: Path): Path {
        requireProviderPath<ArchivePath>(link)
        val target = link.fileSystem.readSymbolicLink(link)
        return ByteStringPath(target.toByteString())
    }

    @Throws(IOException::class)
    override fun copy(source: Path, target: Path, vararg options: CopyOption) {
        requireProviderPath<ArchivePath>(source)
        requireProviderPath<ArchivePath>(target)
        throw ReadOnlyFileSystemException(source.toString(), target.toString(), null)
    }

    @Throws(IOException::class)
    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        requireProviderPath<ArchivePath>(source)
        requireProviderPath<ArchivePath>(target)
        throw ReadOnlyFileSystemException(source.toString(), target.toString(), null)
    }

    @Throws(IOException::class)
    override fun isSameFile(path: Path, path2: Path): Boolean {
        requireProviderPath<ArchivePath>(path)
        if (path == path2) {
            return true
        }
        if (path2 !is ArchivePath) {
            return false
        }
        val fileSystem = path.fileSystem
        if (!fileSystem.archiveFile.isSameFile(path2.fileSystem.archiveFile)) {
            return false
        }
        return path == fileSystem.getPath(path2.toString())
    }

    override fun isHidden(path: Path): Boolean {
        requireProviderPath<ArchivePath>(path)
        return false
    }

    override fun getFileStore(path: Path): FileStore {
        requireProviderPath<ArchivePath>(path)
        val archiveFile = path.fileSystem.archiveFile
        return ArchiveFileStore(archiveFile)
    }

    @Throws(IOException::class)
    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        requireProviderPath<ArchivePath>(path)
        val accessModes = modes.toAccessModes()
        path.fileSystem.getEntry(path)
        if (accessModes.write || accessModes.execute) {
            throw AccessDeniedException(path.toString())
        }
    }

    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption
    ): V? {
        requireProviderPath<ArchivePath>(path)
        if (!supportsFileAttributeView(type)) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return getFileAttributeView(path) as V
    }

    internal fun supportsFileAttributeView(type: Class<out FileAttributeView>): Boolean =
        type.isAssignableFrom(ArchiveFileAttributeView::class.java)

    @Throws(IOException::class)
    override fun <A : BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption
    ): A {
        requireProviderPath<ArchivePath>(path)
        if (!type.isAssignableFrom(ArchiveFileAttributes::class.java)) {
            throw UnsupportedOperationException(type.toString())
        }
        @Suppress("UNCHECKED_CAST")
        return getFileAttributeView(path).readAttributes() as A
    }

    private fun getFileAttributeView(path: ArchivePath): ArchiveFileAttributeView =
        ArchiveFileAttributeView(path)

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption
    ): Map<String, Any> {
        requireProviderPath<ArchivePath>(path)
        throw UnsupportedOperationException()
    }

    override fun setAttribute(
        path: Path,
        attribute: String,
        value: Any,
        vararg options: LinkOption
    ) {
        requireProviderPath<ArchivePath>(path)
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun observe(path: Path, intervalMillis: Long): PathObservable =
        throw UnsupportedOperationException()

    @Throws(IOException::class)
    override fun search(
        directory: Path,
        query: String,
        intervalMillis: Long,
        listener: (List<Path>) -> Unit
    ) {
        requireProviderPath<ArchivePath>(directory)
        WalkFileTreeSearchable.search(directory, query, intervalMillis, listener)
    }
}
