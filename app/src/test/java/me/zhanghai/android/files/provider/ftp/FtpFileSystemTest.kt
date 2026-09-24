/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.ftp

import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.util.Calendar
import java.util.TimeZone
import java8.nio.file.AccessDeniedException
import java8.nio.file.DirectoryStream
import java8.nio.file.FileSystemAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.FileSystemNotFoundException
import java8.nio.file.NoSuchFileException
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributeView
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.DosFileAttributeView
import java8.nio.file.attribute.DosFileAttributes
import me.zhanghai.android.files.provider.ftp.client.Authenticator
import me.zhanghai.android.files.provider.ftp.client.Authority
import me.zhanghai.android.files.provider.ftp.client.Client
import me.zhanghai.android.files.provider.ftp.client.Mode
import me.zhanghai.android.files.provider.ftp.client.Protocol
import org.apache.commons.net.ftp.FTPFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [FtpFileSystem], the attributes made of an `FTPFile`, file systems made from URIs and what the
 * provider reports when it cannot get in at all.
 */
class FtpFileSystemTest {
    @get:Rule val directory = TemporaryFolder()

    private fun authority(port: Int, protocol: Protocol = Protocol.FTP) =
        Authority(protocol, "127.0.0.1", port, "test", Mode.PASSIVE, "UTF-8")

    private fun list(path: Path): List<String> =
        FtpFileSystemProvider.newDirectoryStream(path, AcceptAll)
            .use { stream -> stream.map { it.fileName.toString() } }

    private fun client(password: String?) = Client(
        object : Authenticator {
            override fun getPassword(authority: Authority): String? = password
        }
    )

    @Test
    fun aFileSystemDescribesItself() {
        val fileSystem = FtpFileSystemProvider.getOrNewFileSystem(authority(1))
        try {
            assertTrue(fileSystem.isOpen)
            assertFalse(fileSystem.isReadOnly)
            assertEquals("/", fileSystem.separator)
            assertEquals(listOf(fileSystem.getPath("/")), fileSystem.rootDirectories.toList())
            assertEquals(fileSystem.rootDirectory, fileSystem.defaultDirectory)
            assertTrue("basic" in fileSystem.supportedFileAttributeViews())
            assertSame(FtpFileSystemProvider, fileSystem.provider())
            assertEquals("/a/b/c", fileSystem.getPath("/a", "b", "c").toString())
            assertThrows(UnsupportedOperationException::class.java) { fileSystem.fileStores }
            assertThrows(UnsupportedOperationException::class.java) {
                fileSystem.getPathMatcher("glob:*")
            }
            assertThrows(UnsupportedOperationException::class.java) {
                fileSystem.userPrincipalLookupService
            }
        } finally {
            fileSystem.close()
        }
        assertFalse(fileSystem.isOpen)
        fileSystem.close()
        assertFalse(fileSystem.isOpen)
    }

    @Test
    fun fileSystemsAreEqualWhenTheirServersAre() {
        val fileSystem = FtpFileSystemProvider.getOrNewFileSystem(authority(2))
        try {
            assertSame(fileSystem, FtpFileSystemProvider.getOrNewFileSystem(authority(2)))
            assertEquals(fileSystem, fileSystem)
            assertNotEquals(fileSystem, FtpFileSystemProvider.getOrNewFileSystem(authority(3)))
            assertNotEquals(fileSystem, "127.0.0.1")
            assertEquals(authority(2).hashCode(), fileSystem.hashCode())
        } finally {
            fileSystem.close()
            FtpFileSystemProvider.getOrNewFileSystem(authority(3)).close()
        }
    }

    @Test
    fun aRelativePathHasNoRootAndNoPathIsAJavaFile() {
        val fileSystem = FtpFileSystemProvider.getOrNewFileSystem(authority(4))
        try {
            val relative = fileSystem.getPath("a/b")
            assertFalse(relative.isAbsolute)
            assertNull(relative.root)
            assertEquals(fileSystem.getPath("/"), fileSystem.getPath("/a/b").root)
            assertEquals(fileSystem.getPath("/x/a/b"), fileSystem.getPath("/x").resolve(relative))
            assertThrows(UnsupportedOperationException::class.java) { relative.toFile() }
            assertThrows(UnsupportedOperationException::class.java) { relative.toRealPath() }
            assertTrue(relative.isFtpPath)
        } finally {
            fileSystem.close()
        }
    }

    @Test
    fun aFileSystemIsCreatedFromAUriOnlyOnce() {
        val uri = URI("ftp://test@127.0.0.1:5/")
        assertThrows(FileSystemNotFoundException::class.java) {
            FtpFileSystemProvider.getFileSystem(uri)
        }
        val fileSystem = FtpFileSystemProvider.newFileSystem(uri, emptyMap<String, Any>())
        try {
            assertSame(fileSystem, FtpFileSystemProvider.getFileSystem(uri))
            assertThrows(FileSystemAlreadyExistsException::class.java) {
                FtpFileSystemProvider.newFileSystem(uri, emptyMap<String, Any>())
            }
            assertEquals(authority(5), (fileSystem as FtpFileSystem).authority)
        } finally {
            fileSystem.close()
        }
        assertThrows(FileSystemNotFoundException::class.java) {
            FtpFileSystemProvider.getFileSystem(uri)
        }
    }

    @Test
    fun aSecureSchemeHasItsOwnProviderAndDefaultPort() {
        assertEquals("ftps", FtpsFileSystemProvider.scheme)
        assertEquals("ftpes", FtpesFileSystemProvider.scheme)
        val path = FtpsFileSystemProvider.getPath(URI("ftps://someone@example.com/a")) as FtpPath
        try {
            val authority = (path.fileSystem as FtpFileSystem).authority
            assertEquals(Protocol.FTPS, authority.protocol)
            assertEquals(990, authority.port)
            assertEquals("someone", authority.username)
            assertSame(
                path.fileSystem,
                FtpsFileSystemProvider.getFileSystem(URI("ftps://someone@example.com/"))
            )
            // The secure schemes still share the one provider underneath.
            assertSame(FtpFileSystemProvider, path.fileSystem.provider())
        } finally {
            path.fileSystem.close()
        }
        val explicit = FtpesFileSystemProvider.getPath(URI("ftpes://example.com/")) as FtpPath
        try {
            val authority = (explicit.fileSystem as FtpFileSystem).authority
            assertEquals(Protocol.FTPES, authority.protocol)
            assertEquals(21, authority.port)
            assertEquals("", authority.username)
        } finally {
            explicit.fileSystem.close()
        }
    }

    @Test
    fun anAuthorityOmitsTheDefaultPortAndAnEmptyUser() {
        assertEquals("test@127.0.0.1:2121", authority(2121).toString())
        assertEquals(
            "example.com",
            Authority(Protocol.FTP, "example.com", 21, "", Mode.ACTIVE, "UTF-8").toString()
        )
        assertEquals("test@127.0.0.1", authority(990, Protocol.FTPS).toString())
    }

    @Test
    fun aWrongPasswordIsAccessDeniedAndNotAMissingFile() {
        withFtpFileSystem(directory.root) { fileSystem ->
            FtpFileSystemProvider.client = client("wrong")
            val exception = assertThrows(AccessDeniedException::class.java) {
                list(fileSystem.getPath("/"))
            }
            assertEquals("/", exception.file)
        }
    }

    @Test
    fun noPasswordAtAllFailsBeforeConnecting() {
        withFtpFileSystem(directory.root) { fileSystem ->
            FtpFileSystemProvider.client = client(null)
            val exception = assertThrows(FileSystemException::class.java) {
                list(fileSystem.getPath("/"))
            }
            assertFalse(exception is AccessDeniedException)
            assertTrue(exception.reason, exception.reason!!.startsWith("No password found"))
        }
    }

    @Test
    fun aServerThatIsNotThereIsAFailureAndNotAMissingFile() {
        val port = ServerSocket(0).use { it.localPort }
        FtpFileSystemProvider.client = client("test-only")
        val fileSystem = FtpFileSystemProvider.getOrNewFileSystem(authority(port))
        try {
            val exception = assertThrows(FileSystemException::class.java) {
                list(fileSystem.getPath("/"))
            }
            assertFalse(exception is NoSuchFileException)
            assertFalse(exception is AccessDeniedException)
            assertEquals("/", exception.file)
        } finally {
            fileSystem.close()
        }
    }

    @Test
    fun theBasicAttributeViewIsTheOnlyOne() {
        File(directory.root, "file.txt").writeText("hello")
        withFtpFileSystem(directory.root) { fileSystem ->
            val path = fileSystem.getPath("/file.txt")
            assertNull(
                FtpFileSystemProvider.getFileAttributeView(path, DosFileAttributeView::class.java)
            )
            val view =
                FtpFileSystemProvider.getFileAttributeView(path, BasicFileAttributeView::class.java)
            assertEquals(5L, view!!.readAttributes().size())
            assertThrows(UnsupportedOperationException::class.java) {
                FtpFileSystemProvider.readAttributes(path, DosFileAttributes::class.java)
            }
        }
    }

    @Test
    fun attributesTellTheTypeOfAnEntry() {
        val fileSystem = FtpFileSystemProvider.getOrNewFileSystem(authority(6))
        try {
            val path = fileSystem.getPath("/entry")
            val link = FtpFileAttributes.from(entry(FTPFile.SYMBOLIC_LINK_TYPE), path)
            assertTrue(link.isSymbolicLink)
            val unknown = FtpFileAttributes.from(entry(FTPFile.UNKNOWN_TYPE), path)
            assertTrue(unknown.isOther)
            val directory = FtpFileAttributes.from(entry(FTPFile.DIRECTORY_TYPE), path)
            assertTrue(directory.isDirectory)
            assertSame(path, directory.fileKey())
        } finally {
            fileSystem.close()
        }
    }

    @Test
    fun attributesWithoutASizeOrATimeAreZeroAndTheEpoch() {
        val fileSystem = FtpFileSystemProvider.getOrNewFileSystem(authority(7))
        try {
            val attributes: BasicFileAttributes =
                FtpFileAttributes.from(entry(FTPFile.FILE_TYPE), fileSystem.getPath("/f"))
            assertTrue(attributes.isRegularFile)
            assertEquals(0L, attributes.size())
            assertEquals(0L, attributes.lastModifiedTime().toMillis())
            assertEquals(attributes.lastModifiedTime(), attributes.lastAccessTime())
            assertEquals(attributes.lastModifiedTime(), attributes.creationTime())

            val timed = entry(FTPFile.FILE_TYPE).apply {
                size = 42
                timestamp = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                    .apply { timeInMillis = 1_600_000_000_000 }
            }
            val timedAttributes = FtpFileAttributes.from(timed, fileSystem.getPath("/f"))
            assertEquals(42L, timedAttributes.size())
            assertEquals(1_600_000_000_000, timedAttributes.lastModifiedTime().toMillis())
        } finally {
            fileSystem.close()
        }
    }

    private fun entry(type: Int) = FTPFile().apply {
        this.type = type
        name = "entry"
        size = -1
    }

    private object AcceptAll : DirectoryStream.Filter<Path> {
        override fun accept(entry: Path): Boolean = true
    }
}
