/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb

import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.NoSuchFileException
import java8.nio.file.StandardCopyOption
import java8.nio.file.StandardOpenOption
import me.zhanghai.android.files.provider.common.ProgressCopyOption
import me.zhanghai.android.files.provider.smb.client.Authenticator
import me.zhanghai.android.files.provider.smb.client.Authority
import me.zhanghai.android.files.provider.smb.client.Client
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test

/**
 * [SmbCopyMove] against the Samba server `tools/network-tests.py` provisions, through
 * [SmbFileSystemProvider].
 */
class SmbCopyMoveTest {
    private lateinit var fileSystem: SmbFileSystem

    @Before
    fun setUp() {
        val port = System.getProperty("material.smb.port")
        assumeNotNull("Run tools/network-tests.py to provision the SMB fixture", port)
        SmbFileSystemProvider.client = Client(
            object : Authenticator {
                override fun getPassword(authority: Authority) = "test-only"
            }
        )
        fileSystem = SmbFileSystemProvider.getOrNewFileSystem(
            Authority("127.0.0.1", port!!.toInt(), "test", null)
        )
    }

    @After
    fun tearDown() {
        if (!::fileSystem.isInitialized) {
            return
        }
        for (name in listOf("source.txt", "target.txt", "directory", "copy", "moved")) {
            try {
                SmbFileSystemProvider.deleteIfExists(path(name))
            } catch (e: Exception) {
                // The test that made it is the one that reports the failure.
            }
        }
        fileSystem.close()
    }

    private fun path(name: String): SmbPath = fileSystem.getPath("/test/$name")

    private fun write(name: String, content: String) {
        SmbFileSystemProvider.newOutputStream(
            path(name),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        ).use { it.write(content.toByteArray()) }
    }

    private fun read(name: String): String =
        SmbFileSystemProvider.newInputStream(path(name)).use { String(it.readBytes()) }

    private fun exists(name: String): Boolean = try {
        SmbFileSystemProvider.readAttributes(
            path(name),
            java8.nio.file.attribute.BasicFileAttributes::class.java
        )
        true
    } catch (e: NoSuchFileException) {
        false
    }

    @Test
    fun copyWritesTheTargetAndLeavesTheSource() {
        write("source.txt", "hello")
        SmbFileSystemProvider.copy(path("source.txt"), path("target.txt"))
        assertEquals("hello", read("target.txt"))
        assertEquals("hello", read("source.txt"))
    }

    @Test
    fun copyReportsTheBytesItTransferred() {
        write("source.txt", "hello world")
        val progress = mutableListOf<Long>()
        SmbFileSystemProvider.copy(
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
            SmbFileSystemProvider.copy(path("source.txt"), path("target.txt"))
        }
        assertEquals("old", read("target.txt"))
        SmbFileSystemProvider.copy(
            path("source.txt"),
            path("target.txt"),
            StandardCopyOption.REPLACE_EXISTING
        )
        assertEquals("new", read("target.txt"))
    }

    @Test
    fun copyingAFileOntoItselfKeepsIt() {
        write("source.txt", "hello")
        val progress = mutableListOf<Long>()
        SmbFileSystemProvider.copy(
            path("source.txt"),
            path("source.txt"),
            StandardCopyOption.REPLACE_EXISTING,
            ProgressCopyOption(0) { progress += it }
        )
        assertEquals("hello", read("source.txt"))
        assertEquals(listOf(5L), progress)
    }

    @Test
    fun copyingADirectoryCreatesIt() {
        SmbFileSystemProvider.createDirectory(path("directory"))
        SmbFileSystemProvider.copy(path("directory"), path("copy"))
        assertTrue(
            SmbFileSystemProvider.readAttributes(
                path("copy"),
                java8.nio.file.attribute.BasicFileAttributes::class.java
            ).isDirectory
        )
    }

    @Test
    fun copyingAMissingFileFails() {
        assertThrows(NoSuchFileException::class.java) {
            SmbFileSystemProvider.copy(path("source.txt"), path("target.txt"))
        }
        assertFalse(exists("target.txt"))
    }

    @Test
    fun moveRenamesOnTheServer() {
        write("source.txt", "hello")
        SmbFileSystemProvider.move(path("source.txt"), path("target.txt"))
        assertEquals("hello", read("target.txt"))
        assertFalse(exists("source.txt"))
    }

    @Test
    fun moveOntoAnExistingFileNeedsReplaceExistingAndThenReplacesIt() {
        write("source.txt", "new")
        write("target.txt", "old")
        assertThrows(FileAlreadyExistsException::class.java) {
            SmbFileSystemProvider.move(path("source.txt"), path("target.txt"))
        }
        SmbFileSystemProvider.move(
            path("source.txt"),
            path("target.txt"),
            StandardCopyOption.REPLACE_EXISTING
        )
        assertEquals("new", read("target.txt"))
        assertFalse(exists("source.txt"))
    }

    @Test
    fun movingADirectoryRenamesItWithItsContent() {
        SmbFileSystemProvider.createDirectory(path("directory"))
        write("directory/child.txt", "child")
        SmbFileSystemProvider.move(path("directory"), path("moved"))
        assertFalse(exists("directory"))
        assertEquals("child", read("moved/child.txt"))
        SmbFileSystemProvider.delete(path("moved/child.txt"))
    }

    @Test
    fun anAtomicMoveIsTheRenameAndNothingElse() {
        write("source.txt", "hello")
        SmbFileSystemProvider.move(
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
            SmbFileSystemProvider.copy(
                path("source.txt"),
                path("target.txt"),
                StandardCopyOption.ATOMIC_MOVE
            )
        }
    }
}
