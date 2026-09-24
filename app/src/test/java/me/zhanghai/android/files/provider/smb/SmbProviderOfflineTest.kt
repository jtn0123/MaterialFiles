/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import java.net.ServerSocket
import java.net.URI
import java8.nio.file.AccessDeniedException
import java8.nio.file.DirectoryStream
import java8.nio.file.FileSystemAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.FileSystemNotFoundException
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.OpenOption
import java8.nio.file.Path
import java8.nio.file.ProviderMismatchException
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributeView
import java8.nio.file.attribute.DosFileAttributeView
import java8.nio.file.attribute.DosFileAttributes
import java8.nio.file.attribute.PosixFilePermissions
import me.zhanghai.android.files.provider.common.ByteStringPath
import me.zhanghai.android.files.provider.common.InvalidFileNameException
import me.zhanghai.android.files.provider.common.TestPath
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.common.toOpenOptions
import me.zhanghai.android.files.provider.smb.client.Authenticator
import me.zhanghai.android.files.provider.smb.client.Authority
import me.zhanghai.android.files.provider.smb.client.Client
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [SmbFileSystemProvider] decides before it reaches a server - URIs, options, arguments it
 * refuses, whether two paths can be the same file - and what it reports when there is no server.
 */
class SmbProviderOfflineTest {
    private val posixAttribute =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

    private fun <T> withFileSystem(authority: Authority, block: (SmbFileSystem) -> T): T {
        val fileSystem = SmbFileSystemProvider.getOrNewFileSystem(authority)
        try {
            return block(fileSystem)
        } finally {
            fileSystem.close()
        }
    }

    private fun authority(host: String = "server", port: Int = Authority.DEFAULT_PORT) =
        Authority(host, port, "user", null)

    @Test
    fun aUriNamesTheServerTheUserAndTheDomain() {
        val uri = URI("smb", "DOMAIN\\user", "server", 4450, "/share/file", null, null)
        val path = SmbFileSystemProvider.getPath(uri) as SmbPath
        try {
            assertEquals(Authority("server", 4450, "user", "DOMAIN"), path.authority)
            assertEquals("/share/file", path.toString())
        } finally {
            path.fileSystem.close()
        }
    }

    @Test
    fun aUriWithoutAUserOrAPortUsesTheDefaults() {
        val path = SmbFileSystemProvider.getPath(URI("smb://server/share")) as SmbPath
        try {
            assertEquals(Authority("server", 445, "", null), path.authority)
        } finally {
            path.fileSystem.close()
        }
        // An empty domain is no domain.
        val noDomain = SmbFileSystemProvider.getPath(
            URI("smb", "\\user", "server", -1, "/", null, null)
        ) as SmbPath
        try {
            assertEquals(Authority("server", 445, "user", null), noDomain.authority)
        } finally {
            noDomain.fileSystem.close()
        }
    }

    @Test
    fun aFileSystemIsCreatedFromAUriOnlyOnce() {
        val uri = URI("smb://user@uri-server/")
        assertThrows(FileSystemNotFoundException::class.java) {
            SmbFileSystemProvider.getFileSystem(uri)
        }
        val fileSystem = SmbFileSystemProvider.newFileSystem(uri, emptyMap<String, Any>())
        try {
            assertSame(fileSystem, SmbFileSystemProvider.getFileSystem(uri))
            assertThrows(FileSystemAlreadyExistsException::class.java) {
                SmbFileSystemProvider.newFileSystem(uri, emptyMap<String, Any>())
            }
        } finally {
            fileSystem.close()
        }
        assertThrows(FileSystemNotFoundException::class.java) {
            SmbFileSystemProvider.getFileSystem(uri)
        }
    }

    @Test
    fun aUriOfAnotherSchemeIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            SmbFileSystemProvider.getPath(URI("ftp://server/share"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmbFileSystemProvider.getFileSystem(URI("dav://server/"))
        }
    }

    @Test
    fun openOptionsBecomeTheMatchingCreateDisposition() {
        fun disposition(vararg options: OpenOption) =
            options.toOpenOptions().toSmbCreateDisposition()
        assertEquals(SMB2CreateDisposition.FILE_OPEN, disposition())
        assertEquals(SMB2CreateDisposition.FILE_OPEN, disposition(StandardOpenOption.WRITE))
        assertEquals(
            SMB2CreateDisposition.FILE_CREATE,
            disposition(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)
        )
        assertEquals(
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            disposition(
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
        )
        assertEquals(
            SMB2CreateDisposition.FILE_OPEN_IF,
            disposition(StandardOpenOption.WRITE, StandardOpenOption.CREATE)
        )
        assertEquals(
            SMB2CreateDisposition.FILE_OVERWRITE,
            disposition(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        )
    }

    @Test
    fun openOptionsBecomeTheMatchingAccessAttributesAndCreateOptions() {
        val readWrite = setOf<OpenOption>(StandardOpenOption.READ, StandardOpenOption.WRITE)
            .toOpenOptions()
        assertEquals(
            setOf(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
            readWrite.toSmbDesiredAccess()
        )
        assertEquals(emptySet<FileAttributes>(), readWrite.toSmbFileAttributes())
        assertEquals(emptySet<SMB2CreateOptions>(), readWrite.toSmbCreateOptions())
        assertEquals(SMB2ShareAccess.ALL, readWrite.toSmbShareAccess())

        val everything = setOf<OpenOption>(
            StandardOpenOption.WRITE,
            StandardOpenOption.SPARSE,
            StandardOpenOption.DSYNC,
            StandardOpenOption.DELETE_ON_CLOSE
        ).toOpenOptions()
        assertEquals(setOf(AccessMask.GENERIC_WRITE), everything.toSmbDesiredAccess())
        assertEquals(
            setOf(FileAttributes.FILE_ATTRIBUTE_SPARSE_FILE),
            everything.toSmbFileAttributes()
        )
        // Deleting on close implies not following links, which opens the reparse point itself.
        assertEquals(
            setOf(
                SMB2CreateOptions.FILE_WRITE_THROUGH,
                SMB2CreateOptions.FILE_DELETE_ON_CLOSE,
                SMB2CreateOptions.FILE_OPEN_REPARSE_POINT
            ),
            everything.toSmbCreateOptions()
        )
        val createNew = setOf<OpenOption>(
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.SYNC
        ).toOpenOptions()
        assertEquals(
            setOf(SMB2CreateOptions.FILE_WRITE_THROUGH, SMB2CreateOptions.FILE_OPEN_REPARSE_POINT),
            createNew.toSmbCreateOptions()
        )
        assertEquals(
            setOf(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT),
            setOf<OpenOption>(LinkOption.NOFOLLOW_LINKS).toOpenOptions().toSmbCreateOptions()
        )
    }

    @Test
    fun argumentsItCannotHonourAreRefusedBeforeAnyServerIsAsked() {
        withFileSystem(authority()) { fileSystem ->
            val file = fileSystem.getPath("/share/file")
            assertThrows(UnsupportedOperationException::class.java) {
                SmbFileSystemProvider.newByteChannel(
                    file,
                    setOf(StandardOpenOption.READ),
                    posixAttribute
                )
            }
            assertThrows(UnsupportedOperationException::class.java) {
                SmbFileSystemProvider.newFileChannel(file, setOf(StandardOpenOption.READ))
            }
            assertThrows(UnsupportedOperationException::class.java) {
                SmbFileSystemProvider.createDirectory(file, posixAttribute)
            }
            assertThrows(UnsupportedOperationException::class.java) {
                SmbFileSystemProvider.createSymbolicLink(
                    file,
                    ByteStringPath("target".toByteString()),
                    posixAttribute
                )
            }
            assertThrows(ProviderMismatchException::class.java) {
                SmbFileSystemProvider.createSymbolicLink(file, TestPath("/target"))
            }
            assertThrows(UnsupportedOperationException::class.java) {
                SmbFileSystemProvider.getFileStore(file)
            }
            assertThrows(UnsupportedOperationException::class.java) {
                SmbFileSystemProvider.readAttributes(file, "basic:size")
            }
            assertThrows(UnsupportedOperationException::class.java) {
                SmbFileSystemProvider.setAttribute(file, "basic:size", 0L)
            }
            assertThrows(UnsupportedOperationException::class.java) {
                SmbFileSystemProvider.readAttributes(file, DosFileAttributes::class.java)
            }
            assertThrows(ProviderMismatchException::class.java) {
                SmbFileSystemProvider.delete(TestPath("/share/file"))
            }
        }
    }

    @Test
    fun aLinkToAPathOnAnotherPortCannotBeWrittenAsAUncPath() {
        withFileSystem(authority()) { fileSystem ->
            withFileSystem(authority(port = 4450)) { other ->
                val exception = assertThrows(InvalidFileNameException::class.java) {
                    SmbFileSystemProvider.createSymbolicLink(
                        fileSystem.getPath("/share/link"),
                        other.getPath("/share/target")
                    )
                }
                assertEquals("/share/target", exception.file)
            }
        }
    }

    @Test
    fun theBasicAttributeViewIsTheOnlyOne() {
        withFileSystem(authority()) { fileSystem ->
            val file = fileSystem.getPath("/share/file")
            assertNull(
                SmbFileSystemProvider.getFileAttributeView(file, DosFileAttributeView::class.java)
            )
            val view = SmbFileSystemProvider.getFileAttributeView(
                file,
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS
            )!!
            assertEquals("smb", view.name())
            // Setting nothing does not need a server.
            view.setTimes(null, null, null)
        }
    }

    @Test
    fun onlyFilesInTheSameShareOfTheSameServerCanBeTheSame() {
        withFileSystem(authority()) { fileSystem ->
            withFileSystem(authority(host = "other")) { other ->
                val file = fileSystem.getPath("/share/file")
                assertTrue(
                    SmbFileSystemProvider.isSameFile(file, fileSystem.getPath("/share/file"))
                )
                assertFalse(SmbFileSystemProvider.isSameFile(file, TestPath("/share/file")))
                assertFalse(SmbFileSystemProvider.isSameFile(file, other.getPath("/share/file")))
                assertFalse(
                    SmbFileSystemProvider.isSameFile(file, fileSystem.getPath("/other/file"))
                )
                assertFalse(SmbFileSystemProvider.isSameFile(file, fileSystem.getPath("/share")))
                assertFalse(SmbFileSystemProvider.isSameFile(file, fileSystem.getPath("/")))
                assertFalse(
                    SmbFileSystemProvider.isSameFile(
                        fileSystem.getPath("/share"),
                        fileSystem.getPath("/share/.")
                    )
                )
            }
        }
    }

    @Test
    fun aNameStartingWithADotIsHidden() {
        withFileSystem(authority()) { fileSystem ->
            assertTrue(SmbFileSystemProvider.isHidden(fileSystem.getPath("/share/.hidden")))
            assertFalse(SmbFileSystemProvider.isHidden(fileSystem.getPath("/share/shown")))
            assertFalse(SmbFileSystemProvider.isHidden(fileSystem.getPath("/")))
        }
    }

    @Test
    fun aServerThatIsNotThereIsAFailureAndNotAMissingOrForbiddenFile() {
        val port = ServerSocket(0).use { it.localPort }
        SmbFileSystemProvider.client = Client(
            object : Authenticator {
                override fun getPassword(authority: Authority): String = "password"
            }
        )
        withFileSystem(Authority("127.0.0.1", port, "user", null)) { fileSystem ->
            val file = fileSystem.getPath("/share/file")
            val failures = listOf(
                { SmbFileSystemProvider.delete(file) },
                { SmbFileSystemProvider.createDirectory(file) },
                { SmbFileSystemProvider.checkAccess(file) },
                { SmbFileSystemProvider.readSymbolicLink(file) },
                { SmbFileSystemProvider.newDirectoryStream(fileSystem.getPath("/"), AcceptAll) },
                {
                    SmbFileSystemProvider.readAttributes(
                        file,
                        java8.nio.file.attribute.BasicFileAttributes::class.java
                    )
                },
                {
                    SmbFileSystemProvider.newByteChannel(file, setOf(StandardOpenOption.READ))
                }
            ).map { operation -> assertThrows(FileSystemException::class.java) { operation() } }
            for (failure in failures) {
                assertFalse(failure.toString(), failure is NoSuchFileException)
                assertFalse(failure.toString(), failure is AccessDeniedException)
            }
            assertEquals("/share/file", failures.first().file)
        }
    }

    private object AcceptAll : DirectoryStream.Filter<Path> {
        override fun accept(entry: Path): Boolean = true
    }
}
