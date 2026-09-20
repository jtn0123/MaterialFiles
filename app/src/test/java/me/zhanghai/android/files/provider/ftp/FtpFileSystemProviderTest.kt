/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.ftp

import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java8.nio.file.AccessMode
import java8.nio.file.DirectoryStream
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.NotLinkException
import java8.nio.file.Path
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributeView
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [FtpFileSystemProvider] against a real FTP server: what the app can and cannot ask of it. */
class FtpFileSystemProviderTest {
    @get:Rule val directory = TemporaryFolder()

    private val root: File
        get() = directory.root

    @Test
    fun aDirectoryListsWhatIsInIt() {
        File(root, "one.txt").writeText("one")
        File(root, "two.txt").writeText("two")
        File(root, "directory").mkdir()
        withFtpFileSystem(root) { fileSystem ->
            val names = FtpFileSystemProvider
                .newDirectoryStream(fileSystem.getPath("/"), AcceptAll)
                .use { stream -> stream.map { it.fileName.toString() } }
            assertEquals(listOf("directory", "one.txt", "two.txt"), names.sorted())
        }
    }

    @Test
    fun aFilterKeepsOnlyWhatTheCallerAskedFor() {
        File(root, "one.txt").writeText("one")
        File(root, "two.log").writeText("two")
        withFtpFileSystem(root) { fileSystem ->
            val filter = DirectoryStream.Filter<Path> { it.fileName.toString().endsWith(".log") }
            val names = FtpFileSystemProvider
                .newDirectoryStream(fileSystem.getPath("/"), filter)
                .use { stream -> stream.map { it.fileName.toString() } }
            assertEquals(listOf("two.log"), names)
        }
    }

    /**
     * Known limitation: the FTP client library answers a listing the server refused with an
     * empty array, so a directory that is not there looks like an empty one.
     */
    @Test
    fun listingSomethingThatIsNotThereComesBackEmpty() {
        withFtpFileSystem(root) { fileSystem ->
            val names = FtpFileSystemProvider
                .newDirectoryStream(fileSystem.getPath("/missing"), AcceptAll)
                .use { stream -> stream.map { it.fileName.toString() } }
            assertEquals(emptyList<String>(), names)
        }
    }

    @Test
    fun aDirectoryIsCreatedAndDeleted() {
        withFtpFileSystem(root) { fileSystem ->
            FtpFileSystemProvider.createDirectory(fileSystem.getPath("/directory"))
            assertTrue(File(root, "directory").isDirectory)
            FtpFileSystemProvider.delete(fileSystem.getPath("/directory"))
        }
        assertFalse(File(root, "directory").exists())
    }

    @Test
    fun deletingSomethingThatIsNotThereFails() {
        withFtpFileSystem(root) { fileSystem ->
            assertThrows(NoSuchFileException::class.java) {
                FtpFileSystemProvider.delete(fileSystem.getPath("/missing.txt"))
            }
        }
    }

    @Test
    fun aFileIsWrittenAndReadBack() {
        withFtpFileSystem(root) { fileSystem ->
            FtpFileSystemProvider.newOutputStream(
                fileSystem.getPath("/file.txt"),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            ).use { it.write("hello".toByteArray()) }
            assertEquals("hello", File(root, "file.txt").readText())
            val read = FtpFileSystemProvider.newInputStream(fileSystem.getPath("/file.txt"))
                .use { String(it.readBytes()) }
            assertEquals("hello", read)
        }
    }

    @Test
    fun aFileThatMustBeNewIsNotWrittenOverAnExistingOne() {
        File(root, "file.txt").writeText("old")
        withFtpFileSystem(root) { fileSystem ->
            assertThrows(FileAlreadyExistsException::class.java) {
                FtpFileSystemProvider.newOutputStream(
                    fileSystem.getPath("/file.txt"),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
                )
            }
        }
        assertEquals("old", File(root, "file.txt").readText())
    }

    @Test
    fun aChannelReadsFromWhereItIsToldTo() {
        File(root, "file.txt").writeText("hello world")
        withFtpFileSystem(root) { fileSystem ->
            FtpFileSystemProvider.newByteChannel(
                fileSystem.getPath("/file.txt"),
                setOf(StandardOpenOption.READ)
            ).use { channel ->
                assertEquals(11L, channel.size())
                channel.position(6)
                val buffer = ByteBuffer.allocate(5)
                channel.read(buffer)
                assertEquals("world", String(buffer.array()))
                assertEquals(11L, channel.position())
            }
        }
    }

    @Test
    fun aChannelCannotWriteWithoutTruncatingFirst() {
        withFtpFileSystem(root) { fileSystem ->
            assertThrows(UnsupportedOperationException::class.java) {
                FtpFileSystemProvider.newByteChannel(
                    fileSystem.getPath("/file.txt"),
                    setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE)
                )
            }
        }
    }

    @Test
    fun theAttributesOfAFileAreItsTypeSizeAndTime() {
        val file = File(root, "file.txt")
        file.writeText("hello")
        val lastModified = 1_600_000_000_000
        assertTrue(file.setLastModified(lastModified))
        withFtpFileSystem(root) { fileSystem ->
            val attributes = FtpFileSystemProvider.readAttributes(
                fileSystem.getPath("/file.txt"),
                BasicFileAttributes::class.java
            )
            assertTrue(attributes.isRegularFile)
            assertFalse(attributes.isDirectory)
            assertEquals(5L, attributes.size())
            assertEquals(FileTime.fromMillis(lastModified), attributes.lastModifiedTime())
            assertFalse(attributes.isSymbolicLink)
            assertFalse(attributes.isOther)
        }
    }

    @Test
    fun theTimeAFileWasLastModifiedCanBeSetButNotTheOthers() {
        File(root, "file.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            val view = FtpFileSystemProvider.getFileAttributeView(
                fileSystem.getPath("/file.txt"),
                BasicFileAttributeView::class.java
            )!!
            view.setTimes(FileTime.fromMillis(1_600_000_000_000), null, null)
            assertEquals(1_600_000_000_000, File(root, "file.txt").lastModified())
            // Nothing is sent to the server for a file whose times are not being changed.
            view.setTimes(null, null, null)
            assertThrows(UnsupportedOperationException::class.java) {
                view.setTimes(null, FileTime.fromMillis(0), null)
            }
            assertThrows(UnsupportedOperationException::class.java) {
                view.setTimes(null, null, FileTime.fromMillis(0))
            }
        }
    }

    @Test
    fun theTimeOfALinkItselfCannotBeSet() {
        File(root, "file.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            val view = FtpFileSystemProvider.getFileAttributeView(
                fileSystem.getPath("/file.txt"),
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS
            )!!
            assertThrows(UnsupportedOperationException::class.java) {
                view.setTimes(FileTime.fromMillis(0), null, null)
            }
        }
    }

    @Test
    fun aFileThatIsNotALinkHasNoLinkTarget() {
        File(root, "file.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            assertThrows(NotLinkException::class.java) {
                FtpFileSystemProvider.readSymbolicLink(fileSystem.getPath("/file.txt"))
            }
        }
    }

    @Test
    fun aFileCanBeReadWhenItCanBeListed() {
        File(root, "file.txt").writeText("hello")
        withFtpFileSystem(root) { fileSystem ->
            FtpFileSystemProvider.checkAccess(fileSystem.getPath("/file.txt"), AccessMode.READ)
            assertThrows(NoSuchFileException::class.java) {
                FtpFileSystemProvider.checkAccess(fileSystem.getPath("/missing.txt"))
            }
        }
    }

    @Test
    fun theServerIsNotAskedWhetherAFileCanBeWrittenOrRun() {
        withFtpFileSystem(root) { fileSystem ->
            val path = fileSystem.getPath("/file.txt")
            assertThrows(UnsupportedOperationException::class.java) {
                FtpFileSystemProvider.checkAccess(path, AccessMode.WRITE)
            }
            assertThrows(UnsupportedOperationException::class.java) {
                FtpFileSystemProvider.checkAccess(path, AccessMode.EXECUTE)
            }
        }
    }

    @Test
    fun aFileIsTheSameFileAsItself() {
        withFtpFileSystem(root) { fileSystem ->
            assertTrue(
                FtpFileSystemProvider.isSameFile(
                    fileSystem.getPath("/file.txt"),
                    fileSystem.getPath("/file.txt")
                )
            )
            assertFalse(
                FtpFileSystemProvider.isSameFile(
                    fileSystem.getPath("/file.txt"),
                    fileSystem.getPath("/other.txt")
                )
            )
        }
    }

    @Test
    fun aNameStartingWithADotIsHidden() {
        withFtpFileSystem(root) { fileSystem ->
            assertTrue(FtpFileSystemProvider.isHidden(fileSystem.getPath("/.hidden")))
            assertFalse(FtpFileSystemProvider.isHidden(fileSystem.getPath("/shown")))
        }
    }

    @Test
    fun whatFtpCannotDoIsRefusedInsteadOfPretended() {
        withFtpFileSystem(root) { fileSystem ->
            val path = fileSystem.getPath("/file.txt")
            val other = fileSystem.getPath("/other.txt")
            assertThrows(UnsupportedOperationException::class.java) {
                FtpFileSystemProvider.createSymbolicLink(path, other)
            }
            assertThrows(UnsupportedOperationException::class.java) {
                FtpFileSystemProvider.createLink(path, other)
            }
            assertThrows(UnsupportedOperationException::class.java) {
                FtpFileSystemProvider.getFileStore(path)
            }
            assertThrows(UnsupportedOperationException::class.java) {
                FtpFileSystemProvider.readAttributes(path, "basic:*")
            }
            assertThrows(UnsupportedOperationException::class.java) {
                FtpFileSystemProvider.setAttribute(path, "basic:size", 0L)
            }
            assertThrows(UnsupportedOperationException::class.java) {
                FtpFileSystemProvider.newFileChannel(path, setOf(StandardOpenOption.READ))
            }
        }
    }

    @Test
    fun aSearchWalksTheWholeTree() {
        File(root, "hello.txt").writeText("hello")
        File(root, "directory").mkdir()
        File(root, "directory/hello-too.txt").writeText("hello")
        File(root, "directory/other.txt").writeText("other")
        withFtpFileSystem(root) { fileSystem ->
            val found = mutableListOf<String>()
            FtpFileSystemProvider.search(fileSystem.getPath("/"), "hello", 0) { paths ->
                paths.mapTo(found) { it.toString() }
            }
            assertEquals(listOf("/directory/hello-too.txt", "/hello.txt"), found.sorted())
        }
    }

    @Test
    fun aPathIsBuiltFromAUriOfTheServer() {
        val path = FtpFileSystemProvider.getPath(URI.create("ftp://test@127.0.0.1:2121/directory"))
        assertEquals("/directory", path.toString())
        val fileSystem = path.fileSystem as FtpFileSystem
        val authority = fileSystem.authority
        assertEquals("127.0.0.1", authority.host)
        assertEquals(2121, authority.port)
        assertEquals("test", authority.username)
        // The same server is only opened once.
        assertSame(
            fileSystem,
            FtpFileSystemProvider.getPath(URI.create("ftp://test@127.0.0.1:2121/other")).fileSystem
        )
        fileSystem.close()
    }

    @Test
    fun aUriOfAnotherSchemeIsNotAnFtpPath() {
        assertThrows(IllegalArgumentException::class.java) {
            FtpFileSystemProvider.getPath(URI.create("smb://127.0.0.1/share"))
        }
    }

    private object AcceptAll : DirectoryStream.Filter<Path> {
        override fun accept(entry: Path): Boolean = true
    }
}
