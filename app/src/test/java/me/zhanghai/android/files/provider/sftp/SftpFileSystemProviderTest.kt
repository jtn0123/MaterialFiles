/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp

import java.net.URI
import java.nio.ByteBuffer
import java8.nio.file.AccessMode
import java8.nio.file.DirectoryStream
import java8.nio.file.FileSystemException
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.Path
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.common.PosixFileAttributeView
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.PosixFileModeBit.OWNER_EXECUTE
import me.zhanghai.android.files.provider.common.PosixFileModeBit.OWNER_READ
import me.zhanghai.android.files.provider.common.PosixFileModeBit.OWNER_WRITE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test

/**
 * [SftpFileSystemProvider] against the SSH server `tools/network-tests.py` provisions: what the
 * app can ask of a server, and what the server answers for something that is not there.
 */
class SftpFileSystemProviderTest {
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
            SftpFileSystemProvider.newDirectoryStream(path, AcceptAll).use { stream ->
                stream.forEach { deleteRecursively(it as SftpPath) }
            }
        }
        SftpFileSystemProvider.delete(path)
    }

    private val directory: SftpPath
        get() = fileSystem.getPath("/home/test/provider-test")

    private fun path(name: String): SftpPath = fileSystem.getPath("/home/test/provider-test/$name")

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

    @Test
    fun aDirectoryListsWhatIsInItAndTheFilterKeepsWhatWasAskedFor() {
        write("one.txt", "one")
        write("two.log", "two")
        val names = SftpFileSystemProvider.newDirectoryStream(directory, AcceptAll)
            .use { stream -> stream.map { it.fileName.toString() } }
        assertEquals(listOf("one.txt", "two.log"), names.sorted())
        val filter = DirectoryStream.Filter<Path> { it.fileName.toString().endsWith(".log") }
        val logs = SftpFileSystemProvider.newDirectoryStream(directory, filter)
            .use { stream -> stream.map { it.fileName.toString() } }
        assertEquals(listOf("two.log"), logs)
    }

    @Test
    fun listingSomethingThatIsNotThereFails() {
        assertThrows(NoSuchFileException::class.java) {
            SftpFileSystemProvider.newDirectoryStream(path("missing"), AcceptAll)
        }
    }

    @Test
    fun aDirectoryIsCreatedOnceAndThenExists() {
        SftpFileSystemProvider.createDirectory(path("child"))
        assertTrue(
            SftpFileSystemProvider
                .readAttributes(path("child"), BasicFileAttributes::class.java)
                .isDirectory
        )
        // Version 3 of the protocol has no reply for "it is already there", only for "no".
        assertThrows(FileSystemException::class.java) {
            SftpFileSystemProvider.createDirectory(path("child"))
        }
        SftpFileSystemProvider.delete(path("child"))
        assertThrows(NoSuchFileException::class.java) {
            SftpFileSystemProvider.delete(path("child"))
        }
    }

    @Test
    fun aFileIsWrittenAndReadBack() {
        write("file.txt", "hello")
        assertEquals("hello", read("file.txt"))
    }

    @Test
    fun aFileThatMustBeNewIsNotWrittenOverAnExistingOne() {
        write("file.txt", "old")
        // Version 3 of the protocol has no reply for "it is already there", only for "no".
        assertThrows(FileSystemException::class.java) {
            SftpFileSystemProvider.newOutputStream(
                path("file.txt"),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
        }
        assertEquals("old", read("file.txt"))
    }

    @Test
    fun whatIsAppendedGoesAfterWhatIsThere() {
        write("file.txt", "hello")
        SftpFileSystemProvider.newOutputStream(
            path("file.txt"),
            StandardOpenOption.APPEND,
            StandardOpenOption.WRITE
        ).use { it.write(" world".toByteArray()) }
        assertEquals("hello world", read("file.txt"))
    }

    @Test
    fun aChannelReadsAndWritesWhereItIsToldTo() {
        write("file.txt", "hello world")
        SftpFileSystemProvider.newByteChannel(
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
        assertEquals("HELLO world", read("file.txt"))
    }

    @Test
    fun theAttributesOfAFileAreItsTypeSizeTimesAndPosixOwnership() {
        write("file.txt", "hello")
        val attributes = SftpFileSystemProvider.readAttributes(
            path("file.txt"),
            BasicFileAttributes::class.java
        )
        assertTrue(attributes.isRegularFile)
        assertFalse(attributes.isDirectory)
        assertEquals(5L, attributes.size())
        assertTrue(attributes.lastModifiedTime().toMillis() > 0)
        val posixAttributes = attributes as PosixFileAttributes
        // SFTP names neither the owner nor the group, it only numbers them.
        assertTrue(posixAttributes.owner()!!.id > 0)
        assertNull(posixAttributes.owner()!!.name)
        // Created readable and writable for its owner, whatever the server's umask takes away.
        assertTrue(posixAttributes.mode()!!.containsAll(setOf(OWNER_READ, OWNER_WRITE)))
    }

    @Test
    fun theTimesAndTheModeOfAFileCanBeSet() {
        write("file.txt", "hello")
        val view = SftpFileSystemProvider.getFileAttributeView(
            path("file.txt"),
            PosixFileAttributeView::class.java
        )!!
        val lastModifiedTime = FileTime.fromMillis(1_600_000_000_000)
        view.setTimes(lastModifiedTime, null, null)
        assertEquals(lastModifiedTime, view.readAttributes().lastModifiedTime())
        val mode = setOf(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE)
        view.setMode(mode)
        assertEquals(mode, view.readAttributes().mode())
    }

    @Test
    fun aTimeThatTheProtocolCannotSetOnItsOwnIsRefused() {
        write("file.txt", "hello")
        val view = SftpFileSystemProvider.getFileAttributeView(
            path("file.txt"),
            PosixFileAttributeView::class.java
        )!!
        assertThrows(UnsupportedOperationException::class.java) {
            view.setTimes(null, null, FileTime.fromMillis(0))
        }
        // Setting neither time is what a copy does when it has nothing to carry over.
        view.setTimes(null, null, null)
    }

    @Test
    fun aSymbolicLinkIsCreatedAndReadBackAsItsTarget() {
        write("file.txt", "hello")
        SftpFileSystemProvider.createSymbolicLink(path("link.txt"), path("file.txt"))
        assertEquals(
            "/home/test/provider-test/file.txt",
            SftpFileSystemProvider.readSymbolicLink(path("link.txt")).toString()
        )
        assertTrue(
            SftpFileSystemProvider.readAttributes(
                path("link.txt"),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
            ).isSymbolicLink
        )
        // Followed, it is the file it points at.
        assertEquals(
            5L,
            SftpFileSystemProvider
                .readAttributes(path("link.txt"), BasicFileAttributes::class.java)
                .size()
        )
        assertEquals("hello", read("link.txt"))
    }

    @Test
    fun accessIsCheckedByOpeningTheFileWithWhatWasAskedFor() {
        write("file.txt", "hello")
        SftpFileSystemProvider.checkAccess(path("file.txt"), AccessMode.READ, AccessMode.WRITE)
        assertThrows(NoSuchFileException::class.java) {
            SftpFileSystemProvider.checkAccess(path("missing.txt"))
        }
        // SFTP has no way to ask whether a file can be executed.
        assertThrows(UnsupportedOperationException::class.java) {
            SftpFileSystemProvider.checkAccess(path("file.txt"), AccessMode.EXECUTE)
        }
    }

    @Test
    fun aFileIsTheSameFileAsItselfOnTheSameServer() {
        assertTrue(SftpFileSystemProvider.isSameFile(path("file.txt"), path("file.txt")))
        assertFalse(SftpFileSystemProvider.isSameFile(path("file.txt"), path("other.txt")))
    }

    @Test
    fun aNameStartingWithADotIsHidden() {
        assertTrue(SftpFileSystemProvider.isHidden(path(".hidden")))
        assertFalse(SftpFileSystemProvider.isHidden(path("shown")))
    }

    @Test
    fun whatSftpCannotDoIsRefusedInsteadOfPretended() {
        val path = path("file.txt")
        assertThrows(UnsupportedOperationException::class.java) {
            SftpFileSystemProvider.getFileStore(path)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            SftpFileSystemProvider.readAttributes(path, "basic:*")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            SftpFileSystemProvider.setAttribute(path, "basic:size", 0L)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            SftpFileSystemProvider.newFileChannel(path, setOf(StandardOpenOption.READ))
        }
        assertThrows(UnsupportedOperationException::class.java) {
            SftpFileSystemProvider.createLink(path("link.txt"), path)
        }
        assertNull(
            SftpFileSystemProvider.getFileAttributeView(path, UnsupportedView::class.java)
        )
    }

    @Test
    fun aSearchWalksTheWholeTree() {
        write("hello.txt", "hello")
        SftpFileSystemProvider.createDirectory(path("child"))
        write("child/hello-too.txt", "hello")
        write("child/other.txt", "other")
        val found = mutableListOf<String>()
        SftpFileSystemProvider.search(directory, "hello", 0) { paths ->
            paths.mapTo(found) { it.fileName.toString() }
        }
        assertEquals(listOf("hello-too.txt", "hello.txt"), found.sorted())
    }

    @Test
    fun aPathIsBuiltFromAUriOfTheServer() {
        val path = SftpFileSystemProvider.getPath(URI.create("sftp://user@host:2222/home/file"))
        assertEquals("/home/file", path.toString())
        val pathFileSystem = path.fileSystem as SftpFileSystem
        try {
            assertEquals("host", pathFileSystem.authority.host)
            assertEquals(2222, pathFileSystem.authority.port)
            assertEquals("user", pathFileSystem.authority.username)
            // The same server is the same file system.
            assertSame(
                pathFileSystem,
                SftpFileSystemProvider.getPath(URI.create("sftp://user@host:2222/other")).fileSystem
            )
        } finally {
            pathFileSystem.close()
        }
    }

    @Test
    fun aUriOfAnotherSchemeIsNotOurs() {
        assertThrows(IllegalArgumentException::class.java) {
            SftpFileSystemProvider.getPath(URI.create("ftp://user@host/file"))
        }
    }

    private object AcceptAll : DirectoryStream.Filter<Path> {
        override fun accept(entry: Path): Boolean = true
    }

    /** A view no provider of the app supports. */
    private interface UnsupportedView : java8.nio.file.attribute.FileAttributeView
}
