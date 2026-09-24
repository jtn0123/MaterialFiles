/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.File
import java.io.IOException
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.ftp.withFtpFileSystem
import me.zhanghai.android.files.provider.sftp.SftpFileSystemProvider
import me.zhanghai.android.files.provider.sftp.client.Authority
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The [java8.nio.file.Path] helpers, run against the local file system of the tests. */
class PathExtensionsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var fileSystem: LocalTestFileSystem

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("root")
        fileSystem = LocalTestFileSystem(root.toPath())
    }

    private fun path(name: String): LocalTestPath = fileSystem.getPath("/$name")

    @Test
    fun filesAreCreatedWrittenAndReadBack() {
        val file = path("file.txt")
        assertFalse(file.exists())
        assertSame(file, file.createFile())
        assertTrue(file.exists())
        assertTrue(file.isRegularFile())
        assertFalse(file.isDirectory())
        file.newBufferedWriter(Charsets.UTF_8).use { it.write("héllo") }
        assertEquals("héllo", File(root, "file.txt").readText())
        assertEquals(6L, file.size())
        assertEquals("héllo", file.newBufferedReader(Charsets.UTF_8).use { it.readText() })
        assertArrayEquals("héllo".toByteArray(), file.readAllBytes())
        file.newOutputStream(StandardOpenOption.APPEND).use {
            it.write('!'.code)
            it.write(byteArrayOf('?'.code.toByte()))
            it.flush()
        }
        file.newInputStream().use {
            assertEquals(8, it.available())
            // "é" takes two bytes.
            assertEquals(3, it.skip(3))
            assertEquals('l'.code, it.read())
            val bytes = ByteArray(8)
            assertEquals(4, it.read(bytes, 0, 8))
        }
        assertEquals("héllo!?", File(root, "file.txt").readText())
    }

    @Test
    fun aByteChannelSeesTheWholeFile() {
        val file = path("file")
        file.newByteChannel(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
            it.write(java.nio.ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
        }
        file.newByteChannel(setOf(StandardOpenOption.READ)).use { assertEquals(3L, it.size()) }
    }

    @Test
    fun anOperationTheProviderCannotDoIsAnIOException() {
        val fileSystem = SftpFileSystemProvider.getOrNewFileSystem(Authority("server", 22, "u"))
        try {
            val file = fileSystem.getPath("/file")
            for (open in listOf(
                { file.newByteChannel(StandardOpenOption.WRITE, StandardOpenOption.DSYNC) },
                { file.newByteChannel(setOf(StandardOpenOption.WRITE, StandardOpenOption.DSYNC)) }
            )) {
                val exception = assertThrows(IOException::class.java) { open() }
                assertTrue(exception.cause is UnsupportedOperationException)
            }
        } finally {
            fileSystem.close()
        }
    }

    @Test
    fun directoriesAreCreatedListedAndDeleted() {
        path("a").createDirectory()
        path("a/b").createDirectory()
        assertTrue(path("a/b").isDirectory())
        path("a/d").createDirectory()
        val names = path("a").newDirectoryStream().use { stream ->
            stream.map { it.fileName.toString() }.sorted()
        }
        assertEquals(listOf("b", "d"), names)
        path("a/d").delete()
        path("a/d").deleteIfExists()
        assertThrows(NoSuchFileException::class.java) { path("a/d").delete() }
    }

    @Test
    fun copyingAndMovingWithinOneProvider() {
        File(root, "source").writeText("content")
        path("source").copyTo(path("copy"))
        path("source").moveTo(path("moved"))
        assertEquals("content", File(root, "copy").readText())
        assertEquals("content", File(root, "moved").readText())
        assertFalse(File(root, "source").exists())
    }

    @Test
    fun copyingAndMovingToAnotherProvider() {
        val ftpRoot = temporaryFolder.newFolder("ftp")
        File(root, "source").writeText("content")
        withFtpFileSystem(ftpRoot) { ftp ->
            path("source").copyTo(ftp.getPath("/copy"))
            path("source").moveTo(ftp.getPath("/moved"))
        }
        assertEquals("content", File(ftpRoot, "copy").readText())
        assertEquals("content", File(ftpRoot, "moved").readText())
        assertFalse(File(root, "source").exists())
    }

    @Test
    fun attributesAndAccess() {
        File(root, "file").writeText("x")
        File(root, ".hidden").writeText("x")
        val file = path("file")
        file.checkAccess()
        assertThrows(NoSuchFileException::class.java) { path("missing").checkAccess() }
        assertTrue(file.isReadable)
        assertTrue(file.isWritable)
        assertFalse(path("missing").isReadable)
        assertFalse(file.isHidden)
        assertTrue(path(".hidden").isHidden)
        assertTrue(file.isSameFile(path("file")))
        val time = FileTime.fromMillis(1_000_000_000_000)
        file.setLastModifiedTime(time)
        assertEquals(time, file.getLastModifiedTime())
        assertEquals(time, file.getLastModifiedTime(LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun posixAttributesAreUnsupportedWhereThereIsNoPosixView() {
        File(root, "file").writeText("x")
        val file = path("file")
        val refusals = listOf(
            { file.getMode() },
            { file.setMode(PosixFileMode.FILE_DEFAULT) },
            { file.setOwner(PosixUser(0, null)) },
            { file.setGroup(PosixGroup(0, null)) },
            { file.setSeLinuxContext("u:object_r:file:s0".toByteString()) },
            { file.restoreSeLinuxContext() },
            { file.getFileStore() }
        )
        for (refusal in refusals) {
            assertThrows(UnsupportedOperationException::class.java) { refusal() }
        }
    }

    @Test
    fun aSymbolicLinkTargetIsReadBackAsBytes() {
        File(root, "target").writeText("x")
        val link = path("link")
        link.createSymbolicLink("target".toByteString())
        assertEquals("target".toByteString(), link.readSymbolicLinkByteString())
        assertTrue(link.exists())
        assertTrue(link.isRegularFile())
        assertFalse(link.isRegularFile(LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun resolvingWithinOneFileSystemIsPlainResolve() {
        assertEquals(path("a/b"), path("a").resolveForeign(fileSystem.getPath("b")))
        assertEquals(path("b"), path("a").resolveForeign(path("b")))
    }

    @Test
    fun resolvingAPathOfAnotherFileSystemGoesByItsNames() {
        val other = LocalTestFileSystem(root.toPath())
        val base = path("a")
        assertSame(base, base.resolveForeign(other.getPath("")))
        val absolute = other.getPath("/x")
        assertSame(absolute, base.resolveForeign(absolute))
        val resolved = base.resolveForeign(other.getPath("x/y"))
        assertSame(fileSystem, resolved.fileSystem)
        assertEquals(path("a/x/y"), resolved)
        assertEquals(path("a/x"), base.resolveForeign(TestPath("x")))
        assertThrows(java8.nio.file.ProviderMismatchException::class.java) {
            base.resolveForeign(ByteStringPath("x".toByteString()))
        }
    }
}
