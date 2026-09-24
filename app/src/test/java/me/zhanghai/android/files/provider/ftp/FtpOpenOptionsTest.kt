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

/** How [FtpFileSystemProvider] reads the open options before it asks the server for a stream. */
class FtpOpenOptionsTest {
    @get:Rule val directory = TemporaryFolder()

    private val root: File
        get() = directory.root

    @Test
    fun aDownloadStreamCannotBeAskedToWrite() {
        File(root, "file.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            val path = fileSystem.getPath("/file.txt")
            for (option in listOf<OpenOption>(
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
            )) {
                assertThrows(option.toString(), UnsupportedOperationException::class.java) {
                    FtpFileSystemProvider.newInputStream(path, option)
                }
            }
        }
        assertEquals("hello", File(root, "file.txt").readText())
    }

    @Test
    fun aDownloadStreamIgnoresTheOptionsForWritingAndNeedsTheFile() {
        File(root, "file.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            val content = FtpFileSystemProvider.newInputStream(
                fileSystem.getPath("/file.txt"),
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.CREATE_NEW
            ).use { String(it.readBytes()) }
            assertEquals("hello", content)
            assertThrows(NoSuchFileException::class.java) {
                FtpFileSystemProvider.newInputStream(
                    fileSystem.getPath("/missing.txt"),
                    StandardOpenOption.CREATE
                )
            }
        }
        assertFalse(File(root, "missing.txt").exists())
    }

    @Test
    fun aDownloadStreamNotFollowingLinksReadsAFileThatIsNotOne() {
        File(root, "file.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            val content = FtpFileSystemProvider
                .newInputStream(fileSystem.getPath("/file.txt"), LinkOption.NOFOLLOW_LINKS)
                .use { String(it.readBytes()) }
            assertEquals("hello", content)
        }
    }

    @Test
    fun aFileThatCannotBeCreatedIsReportedForItsPath() {
        withFtpFileSystem(root) { fileSystem ->
            val exception = assertThrows(FileSystemException::class.java) {
                FtpFileSystemProvider.newByteChannel(
                    fileSystem.getPath("/missing/file.txt"),
                    setOf(
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.CREATE
                    )
                )
            }
            assertEquals("/missing/file.txt", exception.file)
        }
    }

    @Test
    fun aChannelThatMayCreateTheFileCreatesIt() {
        withFtpFileSystem(root) { fileSystem ->
            FtpFileSystemProvider.newByteChannel(
                fileSystem.getPath("/file.txt"),
                setOf(
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.CREATE
                )
            ).use { it.write(ByteBuffer.wrap("hello".toByteArray())) }
        }
        assertEquals("hello", File(root, "file.txt").readText())
    }

    @Test
    fun aChannelThatMayNotCreateTheFileNeedsItToExist() {
        withFtpFileSystem(root) { fileSystem ->
            assertThrows(NoSuchFileException::class.java) {
                FtpFileSystemProvider.newByteChannel(
                    fileSystem.getPath("/file.txt"),
                    setOf(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                )
            }
        }
        assertFalse(File(root, "file.txt").exists())
    }

    @Test
    fun aChannelThatMustCreateTheFileFailsOnAnExistingOne() {
        File(root, "file.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            assertThrows(FileAlreadyExistsException::class.java) {
                FtpFileSystemProvider.newByteChannel(
                    fileSystem.getPath("/file.txt"),
                    setOf(
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.CREATE_NEW
                    )
                )
            }
        }
        assertEquals("hello", File(root, "file.txt").readText())
    }

    @Test
    fun aChannelNotFollowingLinksOpensAFileThatIsNotOne() {
        File(root, "file.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            FtpFileSystemProvider.newByteChannel(
                fileSystem.getPath("/file.txt"),
                setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            ).use { assertEquals(5L, it.size()) }
        }
    }

    @Test
    fun aChannelCannotBeCreatedWithAttributes() {
        File(root, "file.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            val attribute = object : FileAttribute<String> {
                override fun name(): String = "test:attribute"

                override fun value(): String = "value"
            }
            assertThrows(UnsupportedOperationException::class.java) {
                FtpFileSystemProvider.newByteChannel(
                    fileSystem.getPath("/file.txt"),
                    setOf(StandardOpenOption.READ),
                    attribute
                )
            }
        }
    }

    @Test
    fun aLinkTargetFromAnotherProviderIsAMismatch() {
        withFtpFileSystem(root) { fileSystem ->
            val link = fileSystem.getPath("/link")
            assertThrows(ProviderMismatchException::class.java) {
                FtpFileSystemProvider.createSymbolicLink(link, TestPath("/target"))
            }
            // A bare target passes the check and only then meets what FTP cannot do.
            assertThrows(UnsupportedOperationException::class.java) {
                FtpFileSystemProvider.createSymbolicLink(
                    link,
                    ByteStringPath("target".toByteString())
                )
            }
        }
    }
}
