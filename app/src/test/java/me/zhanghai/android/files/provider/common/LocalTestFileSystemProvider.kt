/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption as JavaLinkOption
import java.nio.file.OpenOption as JavaOpenOption
import java.nio.file.Path as JavaPath
import java.nio.file.StandardCopyOption as JavaStandardCopyOption
import java.nio.file.StandardOpenOption as JavaStandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView as JavaBasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes as JavaBasicFileAttributes
import java.nio.file.attribute.FileTime as JavaFileTime
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.AccessMode
import java8.nio.file.CopyOption
import java8.nio.file.DirectoryIteratorException
import java8.nio.file.DirectoryNotEmptyException
import java8.nio.file.DirectoryStream
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.FileSystemException
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.OpenOption
import java8.nio.file.Path
import java8.nio.file.StandardCopyOption
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributeView
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.attribute.FileTime
import java8.nio.file.spi.FileSystemProvider

/** The provider behind [LocalTestFileSystem], which forwards to `java.nio.file`. */
internal object LocalTestFileSystemProvider : FileSystemProvider() {
    const val SCHEME = "test-local"

    override fun getScheme(): String = SCHEME

    override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem =
        throw UnsupportedOperationException()

    override fun getFileSystem(uri: URI): FileSystem = throw UnsupportedOperationException()

    override fun getPath(uri: URI): Path = throw UnsupportedOperationException()

    override fun newInputStream(path: Path, vararg options: OpenOption): InputStream =
        translate { Files.newInputStream(path.toJavaPath(), *options.toJavaOptions()) }

    override fun newOutputStream(path: Path, vararg options: OpenOption): OutputStream =
        translate { Files.newOutputStream(path.toJavaPath(), *options.toJavaOptions()) }

    override fun newByteChannel(
        path: Path,
        options: Set<OpenOption>,
        vararg attrs: FileAttribute<*>
    ): SeekableByteChannel = JavaSeekableByteChannel(
        translate {
            Files.newByteChannel(
                path.toJavaPath(),
                *options.toTypedArray().toJavaOptions()
            )
        }
    )

    override fun newDirectoryStream(
        dir: Path,
        filter: DirectoryStream.Filter<in Path>
    ): DirectoryStream<Path> {
        val fileSystem = dir.toLocalTestPath().fileSystem
        val paths = translate {
            Files.newDirectoryStream(dir.toJavaPath()).use { stream ->
                stream.map { fileSystem.getPath("/${fileSystem.rootDirectory.relativize(it)}") }
            }
        }
        val isBroken = dir in fileSystem.brokenListings
        return object : DirectoryStream<Path> {
            override fun iterator(): MutableIterator<Path> {
                val iterator = paths.filterTo(mutableListOf()) { filter.accept(it) }.iterator()
                return if (isBroken) BreakingIterator(iterator) else iterator
            }

            override fun close() {}
        }
    }

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) {
        translate { Files.createDirectory(dir.toJavaPath()) }
    }

    override fun createSymbolicLink(link: Path, target: Path, vararg attrs: FileAttribute<*>) {
        translate {
            Files.createSymbolicLink(
                link.toJavaPath(),
                link.toJavaPath().fileSystem.getPath(target.toString())
            )
        }
    }

    override fun readSymbolicLink(link: Path): Path = ByteStringPath(
        translate { Files.readSymbolicLink(link.toJavaPath()) }.toString()
            .toByteString()
    )

    override fun delete(path: Path) {
        translate { Files.delete(path.toJavaPath()) }
    }

    override fun copy(source: Path, target: Path, vararg options: CopyOption) {
        translate {
            Files.copy(source.toJavaPath(), target.toJavaPath(), *options.toJavaOptions())
        }
    }

    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        translate {
            Files.move(source.toJavaPath(), target.toJavaPath(), *options.toJavaOptions())
        }
    }

    override fun isSameFile(path: Path, path2: Path): Boolean = path2 is LocalTestPath &&
        translate { Files.isSameFile(path.toJavaPath(), path2.toJavaPath()) }

    override fun isHidden(path: Path): Boolean = path.fileName.toString().startsWith(".")

    override fun getFileStore(path: Path): FileStore = throw UnsupportedOperationException()

    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        if (!Files.exists(path.toJavaPath())) {
            throw NoSuchFileException(path.toString())
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption
    ): V? = if (type == BasicFileAttributeView::class.java) {
        LocalTestFileAttributeView(path.toJavaPath(), *options.toJavaOptions()) as V
    } else {
        null
    }

    @Suppress("UNCHECKED_CAST")
    override fun <A : BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption
    ): A = if (type == BasicFileAttributes::class.java) {
        LocalTestFileAttributes(
            translate {
                Files.readAttributes(
                    path.toJavaPath(),
                    JavaBasicFileAttributes::class.java,
                    *options.toJavaOptions()
                )
            }
        ) as A
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

    private fun Path.toLocalTestPath(): LocalTestPath {
        requireProviderPath<LocalTestPath>(this)
        return this
    }

    private fun Path.toJavaPath(): JavaPath = toLocalTestPath().toJavaPath()
}

private class LocalTestFileAttributes(private val attributes: JavaBasicFileAttributes) :
    BasicFileAttributes {
    override fun lastModifiedTime(): FileTime = attributes.lastModifiedTime().toJava8FileTime()

    override fun lastAccessTime(): FileTime = attributes.lastAccessTime().toJava8FileTime()

    override fun creationTime(): FileTime = attributes.creationTime().toJava8FileTime()

    override fun isRegularFile(): Boolean = attributes.isRegularFile

    override fun isDirectory(): Boolean = attributes.isDirectory

    override fun isSymbolicLink(): Boolean = attributes.isSymbolicLink

    override fun isOther(): Boolean = attributes.isOther

    override fun size(): Long = attributes.size()

    override fun fileKey(): Any? = attributes.fileKey()
}

private class LocalTestFileAttributeView(
    private val path: JavaPath,
    private vararg val options: JavaLinkOption
) : BasicFileAttributeView {
    override fun name(): String = "basic"

    override fun readAttributes(): BasicFileAttributes = LocalTestFileAttributes(
        Files.readAttributes(path, JavaBasicFileAttributes::class.java, *options)
    )

    override fun setTimes(
        lastModifiedTime: FileTime?,
        lastAccessTime: FileTime?,
        createTime: FileTime?
    ) {
        Files.getFileAttributeView(path, JavaBasicFileAttributeView::class.java, *options)
            .setTimes(
                lastModifiedTime?.toJavaFileTime(),
                lastAccessTime?.toJavaFileTime(),
                createTime?.toJavaFileTime()
            )
    }
}

private class JavaSeekableByteChannel(private val channel: java.nio.channels.SeekableByteChannel) :
    SeekableByteChannel {
    override fun read(dst: java.nio.ByteBuffer): Int = channel.read(dst)

    override fun write(src: java.nio.ByteBuffer): Int = channel.write(src)

    override fun position(): Long = channel.position()

    override fun position(newPosition: Long): SeekableByteChannel =
        also { channel.position(newPosition) }

    override fun size(): Long = channel.size()

    override fun truncate(size: Long): SeekableByteChannel = also { channel.truncate(size) }

    override fun isOpen(): Boolean = channel.isOpen

    override fun close() = channel.close()
}

private fun JavaFileTime.toJava8FileTime(): FileTime = FileTime.fromMillis(toMillis())

private fun FileTime.toJavaFileTime(): JavaFileTime = JavaFileTime.fromMillis(toMillis())

private fun Array<out OpenOption>.toJavaOptions(): Array<JavaOpenOption> = map {
    when (it) {
        StandardOpenOption.READ -> JavaStandardOpenOption.READ

        StandardOpenOption.WRITE -> JavaStandardOpenOption.WRITE

        StandardOpenOption.CREATE -> JavaStandardOpenOption.CREATE

        StandardOpenOption.CREATE_NEW -> JavaStandardOpenOption.CREATE_NEW

        StandardOpenOption.APPEND -> JavaStandardOpenOption.APPEND

        StandardOpenOption.TRUNCATE_EXISTING -> JavaStandardOpenOption.TRUNCATE_EXISTING

        // The JVM takes this one as an open option too, and opens the link itself.
        LinkOption.NOFOLLOW_LINKS -> JavaLinkOption.NOFOLLOW_LINKS

        else -> throw UnsupportedOperationException(it.toString())
    }
}.toTypedArray()

private fun Array<out CopyOption>.toJavaOptions(): Array<JavaStandardCopyOption> = map {
    when (it) {
        StandardCopyOption.REPLACE_EXISTING -> JavaStandardCopyOption.REPLACE_EXISTING
        StandardCopyOption.COPY_ATTRIBUTES -> JavaStandardCopyOption.COPY_ATTRIBUTES
        StandardCopyOption.ATOMIC_MOVE -> JavaStandardCopyOption.ATOMIC_MOVE
        else -> throw UnsupportedOperationException(it.toString())
    }
}.toTypedArray()

private fun Array<out LinkOption>.toJavaOptions(): Array<JavaLinkOption> = map {
    when (it) {
        LinkOption.NOFOLLOW_LINKS -> JavaLinkOption.NOFOLLOW_LINKS
    }
}.toTypedArray()

/** Lists the first child and then fails, as a listing that broke off does. */
private class BreakingIterator(private val iterator: MutableIterator<Path>) :
    MutableIterator<Path> by iterator {
    private var hasListed = false

    override fun hasNext(): Boolean {
        if (hasListed) {
            throw DirectoryIteratorException(IOException("The listing broke off"))
        }
        return iterator.hasNext()
    }

    override fun next(): Path = iterator.next().also { hasListed = true }
}

/** `java.nio` exceptions mean nothing to callers of the java8 API, so they are translated. */
private inline fun <R> translate(block: () -> R): R = try {
    block()
} catch (e: java.nio.file.NoSuchFileException) {
    throw NoSuchFileException(e.file, e.otherFile, e.reason).apply { initCause(e) }
} catch (e: java.nio.file.FileAlreadyExistsException) {
    throw FileAlreadyExistsException(e.file, e.otherFile, e.reason).apply { initCause(e) }
} catch (e: java.nio.file.DirectoryNotEmptyException) {
    throw DirectoryNotEmptyException(e.file).apply { initCause(e) }
} catch (e: java.nio.file.FileSystemException) {
    throw FileSystemException(e.file, e.otherFile, e.reason).apply { initCause(e) }
}
