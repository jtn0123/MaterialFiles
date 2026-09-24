/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp

import java.net.URI
import java8.nio.file.FileSystemAlreadyExistsException
import java8.nio.file.FileSystemNotFoundException
import java8.nio.file.OpenOption
import java8.nio.file.StandardOpenOption
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.toOpenOptions
import me.zhanghai.android.files.provider.sftp.client.Authority
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SftpPath], [SftpFileSystem], the attributes made of what a server answers and the flags made
 * of open options: the parts of the SFTP provider that never talk to a server.
 */
class SftpPathTest {
    private val authority = Authority("server", Authority.DEFAULT_PORT, "user")

    private val fileSystem = SftpFileSystemProvider.getOrNewFileSystem(authority)

    @After
    fun tearDown() {
        fileSystem.close()
    }

    private fun path(path: String): SftpPath = fileSystem.getPath(path)

    @Test
    fun aPathIsItsOwnRemotePath() {
        assertEquals("/home/user/a b", path("/home/user/a b").remotePath)
        assertEquals("relative/file", path("relative/file").remotePath)
        assertEquals(path("/"), path("/home").root)
        assertNull(path("home").root)
        assertTrue(path("/home").isSftpPath)
        assertSame(fileSystem, path("/home").fileSystem)
        assertThrows(UnsupportedOperationException::class.java) { path("/home").toFile() }
        assertThrows(UnsupportedOperationException::class.java) { path("/home").toRealPath() }
    }

    @Test
    fun aPathSurvivesARoundTripThroughItsUri() {
        val uri = path("/home/user/a b.txt").toUri()
        assertEquals(URI("sftp://user@server/home/user/a%20b.txt"), uri)
        assertEquals(path("/home/user/a b.txt"), SftpFileSystemProvider.getPath(uri))
    }

    @Test
    fun aUriWithoutAUserOrPortUsesTheDefaults() {
        val path = SftpFileSystemProvider.getPath(URI("sftp://example.com:2222/x")) as SftpPath
        try {
            assertEquals(Authority("example.com", 2222, ""), path.authority)
            assertEquals("example.com:2222", path.authority.toString())
        } finally {
            path.fileSystem.close()
        }
        assertEquals("user@server", authority.toString())
    }

    @Test
    fun aFileSystemIsCreatedFromAUriOnlyOnce() {
        val uri = URI("sftp://user@uri-server/")
        assertThrows(FileSystemNotFoundException::class.java) {
            SftpFileSystemProvider.getFileSystem(uri)
        }
        val created = SftpFileSystemProvider.newFileSystem(uri, emptyMap<String, Any>())
        try {
            assertSame(created, SftpFileSystemProvider.getFileSystem(uri))
            assertThrows(FileSystemAlreadyExistsException::class.java) {
                SftpFileSystemProvider.newFileSystem(uri, emptyMap<String, Any>())
            }
        } finally {
            created.close()
        }
        assertFalse(created.isOpen)
        created.close()
        assertThrows(FileSystemNotFoundException::class.java) {
            SftpFileSystemProvider.getFileSystem(uri)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SftpFileSystemProvider.getPath(URI("smb://server/share"))
        }
    }

    @Test
    fun aFileSystemDescribesItself() {
        assertTrue(fileSystem.isOpen)
        assertFalse(fileSystem.isReadOnly)
        assertEquals("/", fileSystem.separator)
        assertEquals(listOf(path("/")), fileSystem.rootDirectories.toList())
        assertEquals(setOf("basic", "posix", "sftp"), fileSystem.supportedFileAttributeViews())
        assertSame(SftpFileSystemProvider, fileSystem.provider())
        assertEquals(path("/a/b/c"), fileSystem.getPath("/a", "b", "c"))
        assertThrows(UnsupportedOperationException::class.java) { fileSystem.fileStores }
        assertThrows(UnsupportedOperationException::class.java) {
            fileSystem.getPathMatcher("glob:*")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            fileSystem.userPrincipalLookupService
        }
        assertEquals(authority.hashCode(), fileSystem.hashCode())
        assertNotEquals(fileSystem, authority)
        val other = SftpFileSystemProvider.getOrNewFileSystem(authority.copy(port = 2222))
        try {
            assertNotEquals(fileSystem, other)
        } finally {
            other.close()
        }
    }

    @Test
    fun theAttributesOfAFileComeFromWhatTheServerSent() {
        val file = path("/file")
        val attributes = SftpFileAttributes.from(
            FileAttributes.Builder()
                .withType(FileMode.Type.REGULAR)
                .withPermissions(0b110_100_000)
                .withSize(42)
                .withAtimeMtime(1_500_000_000, 1_600_000_000)
                .withUIDGID(1000, 100)
                .build(),
            file
        )
        assertEquals(PosixFileType.REGULAR_FILE, attributes.type())
        assertEquals(42L, attributes.size())
        assertEquals(1_600_000_000_000, attributes.lastModifiedTime().toMillis())
        assertEquals(1_500_000_000_000, attributes.lastAccessTime().toMillis())
        // SFTP has no creation time, so the modified time stands in for it.
        assertEquals(attributes.lastModifiedTime(), attributes.creationTime())
        assertEquals(
            setOf(
                PosixFileModeBit.OWNER_READ,
                PosixFileModeBit.OWNER_WRITE,
                PosixFileModeBit.GROUP_READ
            ),
            attributes.mode()
        )
        assertEquals(1000, attributes.owner()!!.id)
        assertEquals(100, attributes.group()!!.id)
        assertSame(file, attributes.fileKey())
        assertNull(attributes.seLinuxContext())
    }

    @Test
    fun theTypeComesFromTheMode() {
        fun type(type: FileMode.Type) = SftpFileAttributes.from(
            FileAttributes.Builder().withType(type).build(),
            path("/entry")
        ).type()
        assertEquals(PosixFileType.DIRECTORY, type(FileMode.Type.DIRECTORY))
        assertEquals(PosixFileType.SYMBOLIC_LINK, type(FileMode.Type.SYMLINK))
        assertEquals(PosixFileType.FIFO, type(FileMode.Type.FIFO_SPECIAL))
        assertEquals(PosixFileType.SOCKET, type(FileMode.Type.SOCKET_SPECIAL))
        assertEquals(PosixFileType.CHARACTER_DEVICE, type(FileMode.Type.CHAR_SPECIAL))
        assertEquals(PosixFileType.BLOCK_DEVICE, type(FileMode.Type.BLOCK_SPECIAL))
    }

    @Test
    fun whatTheServerDidNotSendIsARegularFileOfNothingAtTheEpoch() {
        val attributes = SftpFileAttributes.from(FileAttributes.EMPTY, path("/entry"))
        assertEquals(PosixFileType.REGULAR_FILE, attributes.type())
        assertEquals(0L, attributes.size())
        assertEquals(0L, attributes.lastModifiedTime().toMillis())
        assertNull(attributes.mode())
        assertNull(attributes.owner())
        assertNull(attributes.group())
    }

    @Test
    fun openOptionsBecomeTheMatchingFlags() {
        fun flags(vararg options: OpenOption) = options.toOpenOptions().toSftpFlags()
        assertEquals(setOf(OpenMode.READ), flags())
        assertEquals(
            setOf(OpenMode.READ, OpenMode.WRITE),
            flags(StandardOpenOption.READ, StandardOpenOption.WRITE)
        )
        assertEquals(
            setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC),
            flags(
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
        )
        assertEquals(
            setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL),
            flags(
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.CREATE_NEW
            )
        )
        assertEquals(setOf(OpenMode.WRITE, OpenMode.APPEND), flags(StandardOpenOption.APPEND))
    }

    @Test
    fun theOptionsSftpCannotHonourAreRefused() {
        for (option in listOf(
            StandardOpenOption.DELETE_ON_CLOSE,
            StandardOpenOption.SYNC,
            StandardOpenOption.DSYNC
        )) {
            assertThrows(option.toString(), UnsupportedOperationException::class.java) {
                setOf<OpenOption>(StandardOpenOption.WRITE, option).toOpenOptions().toSftpFlags()
            }
        }
    }
}
