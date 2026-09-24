/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb

import java.net.URI
import java.nio.ByteBuffer
import java8.nio.file.AccessMode
import java8.nio.file.DirectoryStream
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.NoSuchFileException
import java8.nio.file.Path
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributeView
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.smb.client.Authenticator
import me.zhanghai.android.files.provider.smb.client.Authority
import me.zhanghai.android.files.provider.smb.client.Client
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test

/**
 * [SmbFileSystemProvider] against the Samba server `tools/network-tests.py` provisions: what the
 * app can ask of a share, and what a share answers for something that is not there.
 */
class SmbFileSystemProviderTest {
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
        SmbFileSystemProvider.createDirectory(directory)
    }

    @After
    fun tearDown() {
        if (!::fileSystem.isInitialized) {
            return
        }
        try {
            SmbFileSystemProvider.newDirectoryStream(directory, AcceptAll).use { stream ->
                stream.forEach { SmbFileSystemProvider.deleteIfExists(it) }
            }
            SmbFileSystemProvider.deleteIfExists(directory)
        } catch (e: Exception) {
            // The test that left something behind is the one that reports the failure.
        }
        fileSystem.close()
    }

    private val directory: SmbPath
        get() = fileSystem.getPath("/test/provider-test")

    private fun path(name: String): SmbPath = fileSystem.getPath("/test/provider-test/$name")

    private fun write(name: String, content: String) {
        SmbFileSystemProvider.newOutputStream(
            path(name),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        ).use { it.write(content.toByteArray()) }
    }

    @Test
    fun aDirectoryListsWhatIsInItAndTheFilterKeepsWhatWasAskedFor() {
        write("one.txt", "one")
        write("two.log", "two")
        val names = SmbFileSystemProvider.newDirectoryStream(directory, AcceptAll)
            .use { stream -> stream.map { it.fileName.toString() } }
        assertEquals(listOf("one.txt", "two.log"), names.sorted())
        val filter = DirectoryStream.Filter<Path> { it.fileName.toString().endsWith(".log") }
        val logs = SmbFileSystemProvider.newDirectoryStream(directory, filter)
            .use { stream -> stream.map { it.fileName.toString() } }
        assertEquals(listOf("two.log"), logs)
    }

    @Test
    fun listingSomethingThatIsNotThereFails() {
        assertThrows(NoSuchFileException::class.java) {
            SmbFileSystemProvider.newDirectoryStream(path("missing"), AcceptAll)
        }
    }

    @Test
    fun aDirectoryIsCreatedOnceAndThenExists() {
        SmbFileSystemProvider.createDirectory(path("child"))
        assertTrue(
            SmbFileSystemProvider
                .readAttributes(path("child"), BasicFileAttributes::class.java)
                .isDirectory
        )
        assertThrows(FileAlreadyExistsException::class.java) {
            SmbFileSystemProvider.createDirectory(path("child"))
        }
        SmbFileSystemProvider.delete(path("child"))
        assertThrows(NoSuchFileException::class.java) {
            SmbFileSystemProvider.delete(path("child"))
        }
    }

    @Test
    fun aFileIsWrittenAndReadBack() {
        write("file.txt", "hello")
        val content = SmbFileSystemProvider.newInputStream(path("file.txt"))
            .use { String(it.readBytes()) }
        assertEquals("hello", content)
    }

    @Test
    fun aFileThatMustBeNewIsNotWrittenOverAnExistingOne() {
        write("file.txt", "old")
        assertThrows(FileAlreadyExistsException::class.java) {
            SmbFileSystemProvider.newOutputStream(
                path("file.txt"),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
        }
        assertEquals(
            "old",
            SmbFileSystemProvider.newInputStream(path("file.txt"))
                .use { String(it.readBytes()) }
        )
    }

    @Test
    fun aChannelReadsAndWritesWhereItIsToldTo() {
        write("file.txt", "hello world")
        SmbFileSystemProvider.newByteChannel(
            path("file.txt"),
            setOf(StandardOpenOption.READ, StandardOpenOption.WRITE)
        ).use { channel ->
            assertEquals(11L, channel.size())
            channel.position(6)
            val buffer = ByteBuffer.allocate(5)
            channel.read(buffer)
            assertEquals("world", String(buffer.array()))
            channel.position(0)
            channel.write(ByteBuffer.wrap("HELLO".toByteArray()))
        }
        assertEquals(
            "HELLO world",
            SmbFileSystemProvider.newInputStream(path("file.txt"))
                .use { String(it.readBytes()) }
        )
    }

    @Test
    fun theAttributesOfAFileAreItsTypeSizeAndTimes() {
        write("file.txt", "hello")
        val attributes = SmbFileSystemProvider.readAttributes(
            path("file.txt"),
            BasicFileAttributes::class.java
        )
        assertTrue(attributes.isRegularFile)
        assertFalse(attributes.isDirectory)
        assertEquals(5L, attributes.size())
        assertNotNull(attributes.fileKey())
        assertTrue(attributes.lastModifiedTime().toMillis() > 0)
    }

    @Test
    fun theTimesOfAFileCanBeSet() {
        write("file.txt", "hello")
        val view = SmbFileSystemProvider.getFileAttributeView(
            path("file.txt"),
            BasicFileAttributeView::class.java
        )!!
        val lastModifiedTime = FileTime.fromMillis(1_600_000_000_000)
        view.setTimes(lastModifiedTime, null, null)
        assertEquals(lastModifiedTime, view.readAttributes().lastModifiedTime())
    }

    @Test
    fun accessIsCheckedByOpeningTheFileWithWhatWasAskedFor() {
        write("file.txt", "hello")
        SmbFileSystemProvider.checkAccess(path("file.txt"), AccessMode.READ, AccessMode.WRITE)
        assertThrows(NoSuchFileException::class.java) {
            SmbFileSystemProvider.checkAccess(path("missing.txt"))
        }
    }

    @Test
    fun aFileIsTheSameFileAsItselfOnTheSameServer() {
        write("file.txt", "hello")
        write("other.txt", "hello")
        assertTrue(SmbFileSystemProvider.isSameFile(path("file.txt"), path("file.txt")))
        assertFalse(SmbFileSystemProvider.isSameFile(path("file.txt"), path("other.txt")))
        // Another server is another file, without asking either of them.
        val otherFileSystem = SmbFileSystemProvider.getOrNewFileSystem(
            Authority("127.0.0.2", fileSystem.authority.port, "test", null)
        )
        try {
            assertFalse(
                SmbFileSystemProvider.isSameFile(
                    path("file.txt"),
                    otherFileSystem.getPath("/test/provider-test/file.txt")
                )
            )
        } finally {
            otherFileSystem.close()
        }
    }

    @Test
    fun aNameStartingWithADotIsHidden() {
        assertTrue(SmbFileSystemProvider.isHidden(path(".hidden")))
        assertFalse(SmbFileSystemProvider.isHidden(path("shown")))
    }

    @Test
    fun whatSmbCannotDoIsRefusedInsteadOfPretended() {
        val path = path("file.txt")
        assertThrows(UnsupportedOperationException::class.java) {
            SmbFileSystemProvider.getFileStore(path)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            SmbFileSystemProvider.readAttributes(path, "basic:*")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            SmbFileSystemProvider.setAttribute(path, "basic:size", 0L)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            SmbFileSystemProvider.newFileChannel(path, setOf(StandardOpenOption.READ))
        }
    }

    @Test
    fun aSearchWalksTheWholeShare() {
        write("hello.txt", "hello")
        SmbFileSystemProvider.createDirectory(path("child"))
        try {
            write("child/hello-too.txt", "hello")
            write("child/other.txt", "other")
            val found = mutableListOf<String>()
            SmbFileSystemProvider.search(directory, "hello", 0) { paths ->
                paths.mapTo(found) { it.fileName.toString() }
            }
            assertEquals(listOf("hello-too.txt", "hello.txt"), found.sorted())
        } finally {
            SmbFileSystemProvider.deleteIfExists(path("child/hello-too.txt"))
            SmbFileSystemProvider.deleteIfExists(path("child/other.txt"))
            SmbFileSystemProvider.deleteIfExists(path("child"))
        }
    }

    @Test
    fun aPathIsBuiltFromAUriOfTheShare() {
        // A backslash between the domain and the user name, as the app writes it into the URI.
        val path = SmbFileSystemProvider.getPath(URI.create("smb://domain%5Cuser@host/share/file"))
        assertEquals("/share/file", path.toString())
        val authority = (path.fileSystem as SmbFileSystem).authority
        assertEquals("host", authority.host)
        assertEquals("user", authority.username)
        assertEquals("domain", authority.domain)
        (path.fileSystem as SmbFileSystem).close()
    }

    private object AcceptAll : DirectoryStream.Filter<Path> {
        override fun accept(entry: Path): Boolean = true
    }
}
