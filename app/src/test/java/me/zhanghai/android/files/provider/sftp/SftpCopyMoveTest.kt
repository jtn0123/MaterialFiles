/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp

import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.StandardCopyOption
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.common.PosixFileAttributeView
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.ProgressCopyOption
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test

/** [SftpCopyMove] against the SSH server `tools/network-tests.py` provisions. */
class SftpCopyMoveTest {
    private lateinit var fileSystem: SftpFileSystem

    @Before
    fun setUp() {
        val port = sftpTestPort
        assumeNotNull("Run tools/network-tests.py to provision the SFTP fixture", port)
        fileSystem = sftpTestFileSystem(port!!.toInt())
        SftpFileSystemProvider.createDirectory(directory)
    }

    @After
    fun tearDown() {
        if (!::fileSystem.isInitialized) {
            return
        }
        try {
            deleteRecursively(directory)
        } catch (e: Exception) {
            // The test that left something behind is the one that reports the failure.
        }
        fileSystem.close()
    }

    private fun deleteRecursively(path: SftpPath) {
        val attributes = SftpFileSystemProvider.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
        if (attributes.isDirectory) {
            SftpFileSystemProvider.newDirectoryStream(path) { true }.use { stream ->
                stream.forEach { deleteRecursively(it as SftpPath) }
            }
        }
        SftpFileSystemProvider.delete(path)
    }

    private val directory: SftpPath
        get() = fileSystem.getPath("/home/test/copy-move-test")

    private fun path(name: String): SftpPath = fileSystem.getPath("/home/test/copy-move-test/$name")

    private fun write(name: String, content: String) {
        SftpFileSystemProvider.newOutputStream(
            path(name),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        ).use { it.write(content.toByteArray()) }
    }

    private fun read(name: String): String =
        SftpFileSystemProvider.newInputStream(path(name)).use { String(it.readBytes()) }

    private fun exists(name: String): Boolean = try {
        SftpFileSystemProvider.readAttributes(
            path(name),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
        true
    } catch (e: NoSuchFileException) {
        false
    }

    @Test
    fun copyWritesTheTargetAndLeavesTheSource() {
        write("source.txt", "hello")
        SftpFileSystemProvider.copy(path("source.txt"), path("target.txt"))
        assertEquals("hello", read("target.txt"))
        assertEquals("hello", read("source.txt"))
    }

    @Test
    fun copyReportsTheBytesItTransferred() {
        write("source.txt", "hello world")
        val progress = mutableListOf<Long>()
        SftpFileSystemProvider.copy(
            path("source.txt"),
            path("target.txt"),
            ProgressCopyOption(0) { progress += it }
        )
        assertEquals(11L, progress.sum())
    }

    @Test
    fun copyOntoAnExistingFileNeedsReplaceExisting() {
        write("source.txt", "new")
        write("target.txt", "old")
        assertThrows(FileAlreadyExistsException::class.java) {
            SftpFileSystemProvider.copy(path("source.txt"), path("target.txt"))
        }
        assertEquals("old", read("target.txt"))
        SftpFileSystemProvider.copy(
            path("source.txt"),
            path("target.txt"),
            StandardCopyOption.REPLACE_EXISTING
        )
        assertEquals("new", read("target.txt"))
    }

    @Test
    fun replacingLeavesNothingBesideTheTarget() {
        write("source.txt", "new")
        write("target.txt", "old")
        SftpFileSystemProvider.copy(
            path("source.txt"),
            path("target.txt"),
            StandardCopyOption.REPLACE_EXISTING
        )
        val names = SftpFileSystemProvider.newDirectoryStream(directory) { true }
            .use { stream -> stream.map { it.fileName.toString() } }
        assertEquals(listOf("source.txt", "target.txt"), names.sorted())
    }

    @Test
    fun copyingAFileOntoItselfKeepsIt() {
        write("source.txt", "hello")
        val progress = mutableListOf<Long>()
        SftpFileSystemProvider.copy(
            path("source.txt"),
            path("source.txt"),
            StandardCopyOption.REPLACE_EXISTING,
            ProgressCopyOption(0) { progress += it }
        )
        assertEquals("hello", read("source.txt"))
        assertEquals(listOf(5L), progress)
    }

    @Test
    fun copyingADirectoryCreatesItWithTheSameMode() {
        SftpFileSystemProvider.createDirectory(path("directory"))
        SftpFileSystemProvider.copy(path("directory"), path("copy"))
        val attributes = SftpFileSystemProvider.readAttributes(
            path("copy"),
            BasicFileAttributes::class.java
        ) as PosixFileAttributes
        assertTrue(attributes.isDirectory)
        val sourceAttributes = SftpFileSystemProvider.readAttributes(
            path("directory"),
            BasicFileAttributes::class.java
        ) as PosixFileAttributes
        assertEquals(sourceAttributes.mode(), attributes.mode())
    }

    @Test
    fun copyingASymbolicLinkRecreatesIt() {
        write("source.txt", "hello")
        SftpFileSystemProvider.createSymbolicLink(path("link.txt"), path("source.txt"))
        SftpFileSystemProvider.copy(
            path("link.txt"),
            path("copy.txt"),
            LinkOption.NOFOLLOW_LINKS
        )
        assertEquals(
            "/home/test/copy-move-test/source.txt",
            SftpFileSystemProvider.readSymbolicLink(path("copy.txt")).toString()
        )
    }

    @Test
    fun copyingAMissingFileFails() {
        assertThrows(NoSuchFileException::class.java) {
            SftpFileSystemProvider.copy(path("source.txt"), path("target.txt"))
        }
        assertFalse(exists("target.txt"))
    }

    @Test
    fun copyCarriesTheTimesWhenItIsAskedTo() {
        write("source.txt", "hello")
        val view = SftpFileSystemProvider.getFileAttributeView(
            path("source.txt"),
            PosixFileAttributeView::class.java
        )!!
        val lastModifiedTime = FileTime.fromMillis(1_600_000_000_000)
        view.setTimes(lastModifiedTime, null, null)
        SftpFileSystemProvider.copy(
            path("source.txt"),
            path("target.txt"),
            StandardCopyOption.COPY_ATTRIBUTES
        )
        assertEquals(
            lastModifiedTime,
            SftpFileSystemProvider
                .readAttributes(path("target.txt"), BasicFileAttributes::class.java)
                .lastModifiedTime()
        )
    }

    @Test
    fun moveRenamesOnTheServer() {
        write("source.txt", "hello")
        SftpFileSystemProvider.move(path("source.txt"), path("target.txt"))
        assertEquals("hello", read("target.txt"))
        assertFalse(exists("source.txt"))
    }

    @Test
    fun moveOntoAnExistingFileNeedsReplaceExistingAndThenReplacesIt() {
        write("source.txt", "new")
        write("target.txt", "old")
        assertThrows(FileAlreadyExistsException::class.java) {
            SftpFileSystemProvider.move(path("source.txt"), path("target.txt"))
        }
        SftpFileSystemProvider.move(
            path("source.txt"),
            path("target.txt"),
            StandardCopyOption.REPLACE_EXISTING
        )
        assertEquals("new", read("target.txt"))
        assertFalse(exists("source.txt"))
    }

    @Test
    fun movingADirectoryRenamesItWithItsContent() {
        SftpFileSystemProvider.createDirectory(path("directory"))
        write("directory/child.txt", "child")
        SftpFileSystemProvider.move(path("directory"), path("moved"))
        assertFalse(exists("directory"))
        assertEquals("child", read("moved/child.txt"))
    }

    @Test
    fun anAtomicMoveIsTheRenameAndNothingElse() {
        write("source.txt", "hello")
        SftpFileSystemProvider.move(
            path("source.txt"),
            path("target.txt"),
            StandardCopyOption.ATOMIC_MOVE
        )
        assertEquals("hello", read("target.txt"))
        assertFalse(exists("source.txt"))
    }

    @Test
    fun copyCannotBeAskedForAnAtomicMove() {
        write("source.txt", "hello")
        assertThrows(UnsupportedOperationException::class.java) {
            SftpFileSystemProvider.copy(
                path("source.txt"),
                path("target.txt"),
                StandardCopyOption.ATOMIC_MOVE
            )
        }
    }
}
