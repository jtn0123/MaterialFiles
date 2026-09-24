/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.File
import java.nio.file.Files
import java8.nio.file.AtomicMoveNotSupportedException
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.StandardCopyOption
import me.zhanghai.android.files.provider.ftp.FtpFileSystem
import me.zhanghai.android.files.provider.ftp.withFtpFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [ForeignCopyMove] between two real providers: the file system of [LocalTestFileSystem] and a
 * real FTP server. Nothing can be renamed across providers, so every move here is a copy
 * followed by a delete.
 */
class ForeignCopyMoveTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var localRoot: File
    private lateinit var ftpRoot: File
    private lateinit var localFileSystem: LocalTestFileSystem

    @Before
    fun setUp() {
        localRoot = temporaryFolder.newFolder("local")
        ftpRoot = temporaryFolder.newFolder("ftp")
        localFileSystem = LocalTestFileSystem(localRoot.toPath())
    }

    private fun localPath(name: String): LocalTestPath = localFileSystem.getPath("/$name")

    private fun withFtp(block: (FtpFileSystem) -> Unit) = withFtpFileSystem(ftpRoot, block)

    @Test
    fun aFileCopiedToAnotherProviderArrivesWithItsContentAndIsLeftBehind() {
        File(localRoot, "source.txt").writeText("hello")
        withFtp { ftp ->
            ForeignCopyMove.copy(localPath("source.txt"), ftp.getPath("/target.txt"))
        }
        assertEquals("hello", File(ftpRoot, "target.txt").readText())
        assertEquals("hello", File(localRoot, "source.txt").readText())
    }

    @Test
    fun aFileCopiedBackReportsTheBytesItTransferred() {
        File(ftpRoot, "source.txt").writeText("hello world")
        val progress = mutableListOf<Long>()
        withFtp { ftp ->
            ForeignCopyMove.copy(
                ftp.getPath("/source.txt"),
                localPath("target.txt"),
                ProgressCopyOption(0) { progress += it }
            )
        }
        assertEquals("hello world", File(localRoot, "target.txt").readText())
        assertEquals(11L, progress.sum())
    }

    @Test
    fun copyingOntoAnExistingFileNeedsReplaceExisting() {
        File(localRoot, "source.txt").writeText("new")
        File(ftpRoot, "target.txt").writeText("old")
        withFtp { ftp ->
            assertThrows(FileAlreadyExistsException::class.java) {
                ForeignCopyMove.copy(localPath("source.txt"), ftp.getPath("/target.txt"))
            }
        }
        assertEquals("old", File(ftpRoot, "target.txt").readText())
    }

    @Test
    fun aReplacementIsWrittenBesideTheTargetAndLeavesNothingBehind() {
        File(localRoot, "source.txt").writeText("new")
        File(ftpRoot, "target.txt").writeText("old")
        withFtp { ftp ->
            ForeignCopyMove.copy(
                localPath("source.txt"),
                ftp.getPath("/target.txt"),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
        assertEquals("new", File(ftpRoot, "target.txt").readText())
        assertEquals(listOf("target.txt"), ftpRoot.list()!!.sorted())
    }

    @Test
    fun copyingAMissingFileFails() {
        withFtp { ftp ->
            assertThrows(NoSuchFileException::class.java) {
                ForeignCopyMove.copy(localPath("missing.txt"), ftp.getPath("/target.txt"))
            }
        }
        assertFalse(File(ftpRoot, "target.txt").exists())
    }

    @Test
    fun aDirectoryIsCreatedWithoutItsContent() {
        File(ftpRoot, "directory").mkdir()
        File(ftpRoot, "directory/child.txt").writeText("child")
        withFtp { ftp ->
            ForeignCopyMove.copy(ftp.getPath("/directory"), localPath("copy"))
        }
        assertTrue(File(localRoot, "copy").isDirectory)
        // Walking into the directory is the caller's job.
        assertEquals(0, File(localRoot, "copy").list()!!.size)
    }

    @Test
    fun movingToAnotherProviderCopiesAndThenDeletesTheSource() {
        File(localRoot, "source.txt").writeText("hello")
        withFtp { ftp ->
            ForeignCopyMove.move(localPath("source.txt"), ftp.getPath("/target.txt"))
        }
        assertEquals("hello", File(ftpRoot, "target.txt").readText())
        assertFalse(File(localRoot, "source.txt").exists())
    }

    @Test
    fun aMoveToAnotherProviderCannotBeAtomic() {
        File(localRoot, "source.txt").writeText("hello")
        withFtp { ftp ->
            assertThrows(AtomicMoveNotSupportedException::class.java) {
                ForeignCopyMove.move(
                    localPath("source.txt"),
                    ftp.getPath("/target.txt"),
                    StandardCopyOption.ATOMIC_MOVE
                )
            }
        }
        assertEquals("hello", File(localRoot, "source.txt").readText())
        assertFalse(File(ftpRoot, "target.txt").exists())
    }

    @Test
    fun aCopyIsNeverAnAtomicMove() {
        File(localRoot, "source.txt").writeText("hello")
        withFtp { ftp ->
            assertThrows(UnsupportedOperationException::class.java) {
                ForeignCopyMove.copy(
                    localPath("source.txt"),
                    ftp.getPath("/target.txt"),
                    StandardCopyOption.ATOMIC_MOVE
                )
            }
        }
    }

    @Test
    fun theTimeAFileWasLastModifiedIsCarriedOver() {
        val file = File(localRoot, "source.txt")
        file.writeText("hello")
        val lastModified = 1_600_000_000_000
        assertTrue(file.setLastModified(lastModified))
        withFtp { ftp ->
            ForeignCopyMove.copy(localPath("source.txt"), ftp.getPath("/target.txt"))
        }
        assertEquals(lastModified, File(ftpRoot, "target.txt").lastModified())
    }

    /**
     * Two paths of the same provider go through the same algorithm, which is the only way to
     * check what it does with attributes and links precisely.
     */
    @Test
    fun copyAttributesAlsoCarriesTheTimeAFileWasLastRead() {
        val source = File(localRoot, "source.txt")
        source.writeText("hello")
        val sourcePath = source.toPath()
        val lastAccessTime = java.nio.file.attribute.FileTime.fromMillis(1_500_000_000_000)
        val lastModifiedTime = java.nio.file.attribute.FileTime.fromMillis(1_600_000_000_000)
        Files.getFileAttributeView(
            sourcePath,
            java.nio.file.attribute.BasicFileAttributeView::class.java
        ).setTimes(lastModifiedTime, lastAccessTime, null)
        ForeignCopyMove.copy(
            localPath("source.txt"),
            localPath("target.txt"),
            StandardCopyOption.COPY_ATTRIBUTES
        )
        val targetAttributes = Files.readAttributes(
            File(localRoot, "target.txt").toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java
        )
        assertEquals(lastModifiedTime, targetAttributes.lastModifiedTime())
        assertEquals(lastAccessTime, targetAttributes.lastAccessTime())
    }

    @Test
    fun aSymbolicLinkIsRecreatedInsteadOfFollowed() {
        File(localRoot, "target.txt").writeText("hello")
        Files.createSymbolicLink(
            File(localRoot, "link.txt").toPath(),
            localRoot.toPath().fileSystem.getPath("target.txt")
        )
        ForeignCopyMove.copy(
            localPath("link.txt"),
            localPath("copy.txt"),
            LinkOption.NOFOLLOW_LINKS
        )
        val copy = File(localRoot, "copy.txt").toPath()
        assertTrue(Files.isSymbolicLink(copy))
        assertEquals("target.txt", Files.readSymbolicLink(copy).toString())
    }

    /** Nothing tells two providers that a path is the same file, so a copy onto it still runs. */
    @Test
    fun aFileCopiedOntoItselfIsRewritten() {
        File(localRoot, "source.txt").writeText("hello")
        ForeignCopyMove.copy(
            localPath("source.txt"),
            localPath("source.txt"),
            StandardCopyOption.REPLACE_EXISTING
        )
        assertEquals("hello", File(localRoot, "source.txt").readText())
        assertEquals(listOf("source.txt"), localRoot.list()!!.sorted())
    }
}
