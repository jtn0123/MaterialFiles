/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.ftp

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import java8.nio.file.StandardCopyOption
import me.zhanghai.android.files.provider.common.ProgressCopyOption
import org.apache.ftpserver.filesystem.nativefs.NativeFileSystemFactory
import org.apache.ftpserver.ftplet.FileSystemFactory
import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.FtpFile
import org.apache.ftpserver.ftplet.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [FtpCopyMove] against a real FTP server, through [FtpFileSystemProvider]. */
class FtpCopyMoveTest {
    @get:Rule val directory = TemporaryFolder()

    private val root: File
        get() = directory.root

    @Test
    fun copyWritesTheTargetAndLeavesTheSource() {
        File(root, "source.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            FtpFileSystemProvider.copy(
                fileSystem.getPath("/source.txt"),
                fileSystem.getPath("/target.txt")
            )
        }
        assertEquals("hello", File(root, "target.txt").readText())
        assertEquals("hello", File(root, "source.txt").readText())
    }

    @Test
    fun copyReportsTheBytesItTransferred() {
        File(root, "source.txt").writeText("hello world")
        val progress = mutableListOf<Long>()
        withFtpFileSystem(root) { fileSystem ->
            FtpFileSystemProvider.copy(
                fileSystem.getPath("/source.txt"),
                fileSystem.getPath("/target.txt"),
                ProgressCopyOption(0) { progress += it }
            )
        }
        assertEquals(11L, progress.sum())
    }

    @Test
    fun copyOntoAnExistingFileNeedsReplaceExisting() {
        File(root, "source.txt").writeText("new")
        File(root, "target.txt").writeText("old")
        withFtpFileSystem(root) { fileSystem ->
            assertThrows(FileAlreadyExistsException::class.java) {
                FtpFileSystemProvider.copy(
                    fileSystem.getPath("/source.txt"),
                    fileSystem.getPath("/target.txt")
                )
            }
        }
        assertEquals("old", File(root, "target.txt").readText())
    }

    @Test
    fun replacingWritesBesideTheTargetAndLeavesNothingBehind() {
        File(root, "source.txt").writeText("new")
        File(root, "target.txt").writeText("old")
        withFtpFileSystem(root) { fileSystem ->
            FtpFileSystemProvider.copy(
                fileSystem.getPath("/source.txt"),
                fileSystem.getPath("/target.txt"),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
        assertEquals("new", File(root, "target.txt").readText())
        assertEquals(
            listOf("source.txt", "target.txt"),
            root.list()!!.sorted()
        )
    }

    @Test
    fun copyingAFileOntoItselfKeepsIt() {
        File(root, "source.txt").writeText("hello")
        val progress = mutableListOf<Long>()
        withFtpFileSystem(root) { fileSystem ->
            FtpFileSystemProvider.copy(
                fileSystem.getPath("/source.txt"),
                fileSystem.getPath("/source.txt"),
                StandardCopyOption.REPLACE_EXISTING,
                ProgressCopyOption(0) { progress += it }
            )
        }
        assertEquals("hello", File(root, "source.txt").readText())
        assertEquals(listOf(5L), progress)
    }

    @Test
    fun copyingAMissingFileFails() {
        withFtpFileSystem(root) { fileSystem ->
            assertThrows(NoSuchFileException::class.java) {
                FtpFileSystemProvider.copy(
                    fileSystem.getPath("/missing.txt"),
                    fileSystem.getPath("/target.txt")
                )
            }
        }
        assertFalse(File(root, "target.txt").exists())
    }

    @Test
    fun anUploadTheServerCouldNotStoreFailsWhenItIsClosed() {
        File(root, "source.txt").writeText("hello")
        withFtpFileSystem(root, UnwritableFileSystemFactory) { fileSystem ->
            // The server takes the data and only answers 551 once the upload is complete.
            val exception = assertThrows(FileSystemException::class.java) {
                FtpFileSystemProvider.copy(
                    fileSystem.getPath("/source.txt"),
                    fileSystem.getPath("/target.txt")
                )
            }
            assertEquals("/target.txt", exception.file)
        }
        assertFalse(File(root, "target.txt").exists())
    }

    @Test
    fun moveRenamesOnTheServer() {
        File(root, "source.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            FtpFileSystemProvider.move(
                fileSystem.getPath("/source.txt"),
                fileSystem.getPath("/target.txt")
            )
        }
        assertEquals("hello", File(root, "target.txt").readText())
        assertFalse(File(root, "source.txt").exists())
    }

    @Test
    fun moveOntoAnExistingFileNeedsReplaceExistingAndThenReplacesIt() {
        File(root, "source.txt").writeText("new")
        File(root, "target.txt").writeText("old")
        withFtpFileSystem(root) { fileSystem ->
            assertThrows(FileAlreadyExistsException::class.java) {
                FtpFileSystemProvider.move(
                    fileSystem.getPath("/source.txt"),
                    fileSystem.getPath("/target.txt")
                )
            }
            FtpFileSystemProvider.move(
                fileSystem.getPath("/source.txt"),
                fileSystem.getPath("/target.txt"),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
        assertEquals("new", File(root, "target.txt").readText())
        assertFalse(File(root, "source.txt").exists())
    }

    @Test
    fun movingADirectoryRenamesItWithItsContent() {
        File(root, "directory").mkdir()
        File(root, "directory/child.txt").writeText("child")
        withFtpFileSystem(root) { fileSystem ->
            FtpFileSystemProvider.move(
                fileSystem.getPath("/directory"),
                fileSystem.getPath("/moved")
            )
        }
        assertFalse(File(root, "directory").exists())
        assertEquals("child", File(root, "moved/child.txt").readText())
    }

    @Test
    fun movingAFileOntoItselfKeepsIt() {
        File(root, "source.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            FtpFileSystemProvider.move(
                fileSystem.getPath("/source.txt"),
                fileSystem.getPath("/source.txt")
            )
        }
        assertEquals("hello", File(root, "source.txt").readText())
    }

    @Test
    fun anAtomicMoveIsTheRenameAndNothingElse() {
        File(root, "source.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            FtpFileSystemProvider.move(
                fileSystem.getPath("/source.txt"),
                fileSystem.getPath("/target.txt"),
                StandardCopyOption.ATOMIC_MOVE
            )
        }
        assertEquals("hello", File(root, "target.txt").readText())
        assertFalse(File(root, "source.txt").exists())
    }

    @Test
    fun copyCannotBeAskedForAnAtomicMove() {
        File(root, "source.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            assertThrows(UnsupportedOperationException::class.java) {
                FtpFileSystemProvider.copy(
                    fileSystem.getPath("/source.txt"),
                    fileSystem.getPath("/target.txt"),
                    StandardCopyOption.ATOMIC_MOVE
                )
            }
        }
    }

    /** A server file system on which nothing can be written, though it claims otherwise. */
    private object UnwritableFileSystemFactory : FileSystemFactory {
        override fun createFileSystemView(user: User): FileSystemView {
            val view = NativeFileSystemFactory().createFileSystemView(user)
            return object : FileSystemView by view {
                override fun getFile(file: String): FtpFile = UnwritableFile(view.getFile(file))
            }
        }
    }

    private class UnwritableFile(private val file: FtpFile) : FtpFile by file {
        override fun createOutputStream(offset: Long): OutputStream =
            throw IOException("No space left on device")
    }
}
