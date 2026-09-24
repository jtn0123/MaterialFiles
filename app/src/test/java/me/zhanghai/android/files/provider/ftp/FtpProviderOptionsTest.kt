/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.ftp

import java.io.File
import java.nio.ByteBuffer
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.OpenOption
import java8.nio.file.ProviderMismatchException
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.PosixFilePermissions
import me.zhanghai.android.files.provider.common.ByteStringPath
import me.zhanghai.android.files.provider.common.TestPath
import me.zhanghai.android.files.provider.common.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The open options of [FtpFileSystemProvider] against a real FTP server: which ones it refuses
 * up front, which ones it checks against the server, and what a channel does with its writes.
 */
class FtpProviderOptionsTest {
    @get:Rule val directory = TemporaryFolder()

    private val root: File
        get() = directory.root

    private val posixAttribute: FileAttribute<*> =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

    private fun FtpFileSystem.read(path: String, vararg options: OpenOption): String =
        FtpFileSystemProvider.newInputStream(getPath(path), *options)
            .use { String(it.readBytes()) }

    private fun FtpFileSystem.write(path: String, content: String, vararg options: OpenOption) {
        FtpFileSystemProvider.newOutputStream(getPath(path), *options)
            .use { it.write(content.toByteArray()) }
    }

    @Test
    fun anInputStreamRefusesEveryWayOfWriting() {
        File(root, "file.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            for (option in listOf(
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                StandardOpenOption.DELETE_ON_CLOSE,
                StandardOpenOption.SYNC,
                StandardOpenOption.DSYNC
            )) {
                assertThrows(option.toString(), UnsupportedOperationException::class.java) {
                    fileSystem.read("/file.txt", option)
                }
            }
        }
        assertEquals("hello", File(root, "file.txt").readText())
    }

    /** As in java.nio, the options that only make sense for writing are ignored for reading. */
    @Test
    fun anInputStreamIgnoresTheOptionsForWriting() {
        File(root, "file.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            assertEquals(
                "hello",
                fileSystem.read("/file.txt", StandardOpenOption.TRUNCATE_EXISTING)
            )
            assertEquals("hello", fileSystem.read("/file.txt", StandardOpenOption.CREATE_NEW))
            assertThrows(NoSuchFileException::class.java) {
                fileSystem.read("/new.txt", StandardOpenOption.CREATE)
            }
        }
        assertEquals("hello", File(root, "file.txt").readText())
        assertFalse(File(root, "new.txt").exists())
    }

    @Test
    fun anInputStreamWithoutFollowingLinksReadsAPlainFile() {
        File(root, "file.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            assertEquals("hello", fileSystem.read("/file.txt", LinkOption.NOFOLLOW_LINKS))
            assertThrows(NoSuchFileException::class.java) {
                fileSystem.read("/missing.txt", LinkOption.NOFOLLOW_LINKS)
            }
        }
    }

    @Test
    fun anOutputStreamWithoutOptionsCreatesAndReplaces() {
        File(root, "file.txt").writeText("old content")
        withFtpFileSystem(root) { fileSystem ->
            fileSystem.write("/file.txt", "new")
            fileSystem.write("/created.txt", "created")
        }
        assertEquals("new", File(root, "file.txt").readText())
        assertEquals("created", File(root, "created.txt").readText())
    }

    @Test
    fun anOutputStreamMustTruncateOrCreateANewFile() {
        File(root, "file.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            assertThrows(UnsupportedOperationException::class.java) {
                fileSystem.write("/file.txt", "x", StandardOpenOption.WRITE)
            }
            assertThrows(UnsupportedOperationException::class.java) {
                fileSystem.write("/file.txt", "x", StandardOpenOption.APPEND)
            }
            assertThrows(UnsupportedOperationException::class.java) {
                fileSystem.write(
                    "/file.txt",
                    "x",
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.SYNC
                )
            }
        }
        assertEquals("hello", File(root, "file.txt").readText())
    }

    @Test
    fun anOutputStreamWithoutCreateNeedsTheFileToExist() {
        withFtpFileSystem(root) { fileSystem ->
            assertThrows(NoSuchFileException::class.java) {
                fileSystem.write("/missing.txt", "x", StandardOpenOption.TRUNCATE_EXISTING)
            }
            fileSystem.write("/new.txt", "new", StandardOpenOption.CREATE_NEW)
        }
        assertFalse(File(root, "missing.txt").exists())
        assertEquals("new", File(root, "new.txt").readText())
    }

    @Test
    fun aFileChannelRefusesAttributesAndUnsupportedOptionsToo() {
        withFtpFileSystem(root) { fileSystem ->
            val path = fileSystem.getPath("/file.txt")
            assertThrows(UnsupportedOperationException::class.java) {
                FtpFileSystemProvider.newFileChannel(
                    path,
                    setOf(StandardOpenOption.READ),
                    posixAttribute
                )
            }
            assertThrows(UnsupportedOperationException::class.java) {
                FtpFileSystemProvider.newFileChannel(path, setOf(StandardOpenOption.DSYNC))
            }
        }
    }

    @Test
    fun aByteChannelOfAMissingFileForWritingNeedsCreate() {
        withFtpFileSystem(root) { fileSystem ->
            assertThrows(NoSuchFileException::class.java) {
                FtpFileSystemProvider.newByteChannel(
                    fileSystem.getPath("/missing.txt"),
                    setOf(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                )
            }
        }
        assertFalse(File(root, "missing.txt").exists())
    }

    @Test
    fun aByteChannelForANewFileFailsForAnExistingOne() {
        File(root, "file.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            assertThrows(FileAlreadyExistsException::class.java) {
                FtpFileSystemProvider.newByteChannel(
                    fileSystem.getPath("/file.txt"),
                    setOf(
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.TRUNCATE_EXISTING
                    )
                )
            }
        }
        assertEquals("hello", File(root, "file.txt").readText())
    }

    @Test
    fun aByteChannelCreatesAMissingFileAndWritesWhereItIsToldTo() {
        withFtpFileSystem(root) { fileSystem ->
            FtpFileSystemProvider.newByteChannel(
                fileSystem.getPath("/file.txt"),
                setOf(
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
            ).use { channel ->
                assertEquals(5, channel.write(ByteBuffer.wrap("hello".toByteArray())))
                channel.position(1)
                assertEquals(4, channel.write(ByteBuffer.wrap("ELLO".toByteArray())))
                assertEquals(5L, channel.size())
                channel.truncate(2)
                assertEquals(2L, channel.size())
                assertEquals(2L, channel.position())
            }
        }
        assertEquals("hE", File(root, "file.txt").readText())
    }

    /**
     * Writing needs TRUNCATE_EXISTING, which java.nio does not allow together with APPEND, so a
     * channel can never append.
     */
    @Test
    fun aByteChannelCannotAppend() {
        File(root, "file.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            val path = fileSystem.getPath("/file.txt")
            assertThrows(UnsupportedOperationException::class.java) {
                FtpFileSystemProvider.newByteChannel(path, setOf(StandardOpenOption.APPEND))
            }
            assertThrows(IllegalStateException::class.java) {
                FtpFileSystemProvider.newByteChannel(
                    path,
                    setOf(StandardOpenOption.APPEND, StandardOpenOption.TRUNCATE_EXISTING)
                )
            }
        }
        assertEquals("hello", File(root, "file.txt").readText())
    }

    @Test
    fun aByteChannelWithoutFollowingLinksOpensAPlainFile() {
        File(root, "file.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            FtpFileSystemProvider.newByteChannel(
                fileSystem.getPath("/file.txt"),
                setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            ).use { assertEquals(5L, it.size()) }
            assertThrows(NoSuchFileException::class.java) {
                FtpFileSystemProvider.newByteChannel(
                    fileSystem.getPath("/missing.txt"),
                    setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
                )
            }
        }
    }

    @Test
    fun aByteChannelRefusesFileAttributes() {
        File(root, "file.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            assertThrows(UnsupportedOperationException::class.java) {
                FtpFileSystemProvider.newByteChannel(
                    fileSystem.getPath("/file.txt"),
                    setOf(StandardOpenOption.READ),
                    posixAttribute
                )
            }
        }
    }

    @Test
    fun aDirectoryIsCreatedOnlyOnceAndWithoutAttributes() {
        withFtpFileSystem(root) { fileSystem ->
            FtpFileSystemProvider.createDirectory(fileSystem.getPath("/dir"))
            assertThrows(FileSystemException::class.java) {
                FtpFileSystemProvider.createDirectory(fileSystem.getPath("/dir"))
            }
            assertThrows(UnsupportedOperationException::class.java) {
                FtpFileSystemProvider.createDirectory(fileSystem.getPath("/other"), posixAttribute)
            }
        }
        assertTrue(File(root, "dir").isDirectory)
        assertFalse(File(root, "other").exists())
    }

    @Test
    fun aLinkToAnotherProvidersPathIsAMismatch() {
        withFtpFileSystem(root) { fileSystem ->
            val link = fileSystem.getPath("/link")
            assertThrows(ProviderMismatchException::class.java) {
                FtpFileSystemProvider.createSymbolicLink(link, TestPath("/target"))
            }
            assertThrows(UnsupportedOperationException::class.java) {
                FtpFileSystemProvider.createSymbolicLink(link, ByteStringPath("t".toByteString()))
            }
            assertThrows(UnsupportedOperationException::class.java) {
                FtpFileSystemProvider.createSymbolicLink(
                    link,
                    fileSystem.getPath("/target"),
                    posixAttribute
                )
            }
        }
    }

    @Test
    fun deletingANonEmptyDirectoryFailsAndKeepsIt() {
        File(root, "dir").mkdir()
        File(root, "dir/file.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            assertThrows(FileSystemException::class.java) {
                FtpFileSystemProvider.delete(fileSystem.getPath("/dir"))
            }
        }
        assertEquals("hello", File(root, "dir/file.txt").readText())
    }
}
