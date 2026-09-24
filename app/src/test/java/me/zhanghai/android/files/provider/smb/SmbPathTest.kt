/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb

import com.hierynomus.msdtyp.FileTime as SmbFileTime
import com.hierynomus.msfscc.FileAttributes
import java.net.URI
import me.zhanghai.android.files.provider.smb.client.Authority
import me.zhanghai.android.files.provider.smb.client.FileInformation
import me.zhanghai.android.files.provider.smb.client.ShareInformation
import me.zhanghai.android.files.provider.smb.client.ShareType
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
 * [SmbPath], [SmbFileSystem], [SmbFileKey] and the attributes made of what a server answers: the
 * parts of the SMB provider that never talk to a server.
 */
class SmbPathTest {
    private val authority = Authority("server", Authority.DEFAULT_PORT, "user", null)

    private val fileSystem = SmbFileSystemProvider.getOrNewFileSystem(authority)

    @After
    fun tearDown() {
        fileSystem.close()
    }

    private fun path(path: String): SmbPath = fileSystem.getPath(path)

    @Test
    fun theFirstNameIsTheShareAndTheRestIsTheWindowsPathInIt() {
        val sharePath = path("/share/dir/file.txt").sharePath!!
        assertEquals("share", sharePath.name)
        assertEquals("dir\\file.txt", sharePath.path)
        val share = path("/share").sharePath!!
        assertEquals("share", share.name)
        assertEquals("", share.path)
        assertNull(path("/").sharePath)
    }

    @Test
    fun onlyAnAbsolutePathHasAShare() {
        assertThrows(IllegalStateException::class.java) { path("share/file").sharePath }
    }

    @Test
    fun anAbsolutePathIsAUncPathAndARelativeOneUsesBackslashes() {
        assertEquals(
            "\\\\server\\share\\dir\\file.txt",
            path("/share/dir/file.txt").toWindowsPath()
        )
        assertEquals("\\\\server\\", path("/").toWindowsPath())
        assertEquals("dir\\file.txt", path("dir/file.txt").toWindowsPath())
    }

    @Test
    fun aUncPathCannotNameAPort() {
        val other = SmbFileSystemProvider.getOrNewFileSystem(authority.copy(port = 4450))
        try {
            assertThrows(IllegalStateException::class.java) {
                other.getPath("/share/file").toWindowsPath()
            }
            // A relative path does not name the server, so the port does not matter.
            assertEquals("a\\b", other.getPath("a/b").toWindowsPath())
        } finally {
            other.close()
        }
    }

    @Test
    fun aPathKnowsItsRootItsFileSystemAndThatItIsAnSmbPath() {
        val file = path("/share/file")
        assertEquals(path("/"), file.root)
        assertNull(path("share/file").root)
        assertSame(fileSystem, file.fileSystem)
        assertEquals(authority, file.authority)
        assertTrue(file.isSmbPath)
        assertThrows(UnsupportedOperationException::class.java) { file.toFile() }
        assertThrows(UnsupportedOperationException::class.java) { file.toRealPath() }
        assertEquals(path("/"), path("share").toAbsolutePath().root)
    }

    @Test
    fun aUriNamesTheServerAndOnlyTheOptionsThatAreNotTheDefault() {
        assertEquals(
            URI("smb://user@server/share/a%20b"),
            path("/share/a b").toUri()
        )
        val encrypted = SmbFileSystemProvider.getOrNewFileSystem(
            Authority("server", 4450, "", "DOMAIN", encrypt = true)
        )
        try {
            val uri = encrypted.getPath("/share").toUri()
            assertEquals("smb", uri.scheme)
            assertEquals("DOMAIN\\", uri.userInfo)
            assertEquals(4450, uri.port)
            assertEquals("encrypt=true", uri.query)
        } finally {
            encrypted.close()
        }
    }

    @Test
    fun aPathSurvivesARoundTripThroughItsUri() {
        val domain = SmbFileSystemProvider.getOrNewFileSystem(
            Authority("server", 4450, "user", "DOMAIN")
        )
        try {
            for (path in listOf(path("/share/dir/a b.txt"), domain.getPath("/share/x"))) {
                assertEquals(path, SmbFileSystemProvider.getPath(path.toUri()))
            }
        } finally {
            domain.close()
        }
    }

    @Test
    fun anAuthorityOmitsTheDefaultPortAndAnEmptyUserButNotADomain() {
        assertEquals("user@server", authority.toString())
        assertEquals("server:4450", Authority("server", 4450, "", null).toString())
        assertEquals("D\\@server", Authority("server", 445, "", "D").toString())
        assertEquals("D\\u@server", Authority("server", 445, "u", "D").toString())
    }

    @Test
    fun aFileSystemDescribesItself() {
        assertTrue(fileSystem.isOpen)
        assertFalse(fileSystem.isReadOnly)
        assertEquals("/", fileSystem.separator)
        assertEquals(listOf(path("/")), fileSystem.rootDirectories.toList())
        assertEquals(path("/"), fileSystem.defaultDirectory)
        assertEquals(setOf("basic", "smb"), fileSystem.supportedFileAttributeViews())
        assertSame(SmbFileSystemProvider, fileSystem.provider())
        assertEquals(path("/share/a/b"), fileSystem.getPath("/share", "a", "b"))
        assertThrows(UnsupportedOperationException::class.java) { fileSystem.fileStores }
        assertThrows(UnsupportedOperationException::class.java) {
            fileSystem.getPathMatcher("glob:*")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            fileSystem.userPrincipalLookupService
        }
        assertEquals(authority.hashCode(), fileSystem.hashCode())
        assertNotEquals(fileSystem, authority)
    }

    @Test
    fun aClosedFileSystemIsForgottenAndClosingItAgainIsHarmless() {
        val other = SmbFileSystemProvider.getOrNewFileSystem(authority.copy(host = "other"))
        other.close()
        assertFalse(other.isOpen)
        other.close()
        val reopened = SmbFileSystemProvider.getOrNewFileSystem(authority.copy(host = "other"))
        try {
            assertTrue(reopened.isOpen)
            // A new file system for the same server is still equal to the closed one.
            assertEquals(other, reopened)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun fileKeysWithAnIdAreEqualWithinAShareWhateverThePath() {
        val key = SmbFileKey(path("/share/a"), 42)
        assertEquals(key, SmbFileKey(path("/share/dir/b"), 42))
        assertEquals(key.hashCode(), SmbFileKey(path("/share/dir/b"), 42).hashCode())
        assertNotEquals(key, SmbFileKey(path("/other/a"), 42))
        assertNotEquals(key, SmbFileKey(path("/share/a"), 43))
        assertNotEquals(key, SmbFileKey(path("/share/a"), 0))
        assertNotEquals(key, path("/share/a"))
        assertEquals(key, key)
    }

    @Test
    fun fileKeysWithoutAnIdAreEqualWhenTheirPathsAre() {
        val key = SmbFileKey(path("/share/a"), 0)
        assertEquals(key, SmbFileKey(path("/share/a"), 0))
        assertEquals(path("/share/a").hashCode(), key.hashCode())
        assertNotEquals(key, SmbFileKey(path("/share/b"), 0))
    }

    @Test
    fun theAttributesOfAFileComeFromItsInformation() {
        val file = path("/share/file")
        val attributes = SmbFileAttributes.from(
            information(FileAttributes.FILE_ATTRIBUTE_ARCHIVE.value),
            file
        )
        assertTrue(attributes.isRegularFile)
        assertEquals(1_000L, attributes.creationTime().toMillis())
        assertEquals(2_000L, attributes.lastAccessTime().toMillis())
        assertEquals(3_000L, attributes.lastModifiedTime().toMillis())
        assertEquals(10L, attributes.size())
        assertEquals(FileAttributes.FILE_ATTRIBUTE_ARCHIVE.value, attributes.attributes())
        assertEquals(SmbFileKey(file, 7), attributes.fileKey())
    }

    @Test
    fun aReparsePointIsALinkEvenWhenItIsADirectory() {
        val file = path("/share/file")
        val directory = SmbFileAttributes.from(
            information(FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value),
            file
        )
        assertTrue(directory.isDirectory)
        val link = SmbFileAttributes.from(
            information(
                FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value or
                    FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value
            ),
            file
        )
        assertTrue(link.isSymbolicLink)
        assertFalse(link.isDirectory)
    }

    @Test
    fun aDiskShareIsADirectoryAndAnyOtherShareIsSomethingElse() {
        val share = path("/share")
        val disk = SmbShareFileAttributes.from(ShareInformation(ShareType.DISK, null), share)
        assertTrue(disk.isDirectory)
        assertEquals(0L, disk.size())
        assertEquals(0L, disk.lastModifiedTime().toMillis())
        assertSame(share, disk.fileKey())
        assertNull(disk.totalSpace())
        assertNull(disk.usableSpace())
        assertNull(disk.unallocatedSpace())
        val printer = SmbShareFileAttributes.from(ShareInformation(ShareType.PRINTER, null), share)
        assertTrue(printer.isOther)
        assertTrue(
            SmbShareFileAttributes.from(ShareInformation(ShareType.PIPE, null), share).isOther
        )
    }

    private fun information(attributes: Long) = FileInformation(
        SmbFileTime.ofEpochMillis(1_000),
        SmbFileTime.ofEpochMillis(2_000),
        SmbFileTime.ofEpochMillis(3_000),
        SmbFileTime.ofEpochMillis(4_000),
        10,
        attributes,
        7
    )
}
