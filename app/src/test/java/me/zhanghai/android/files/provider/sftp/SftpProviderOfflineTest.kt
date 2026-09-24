/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp

import java.net.ServerSocket
import java8.nio.file.AccessDeniedException
import java8.nio.file.AccessMode
import java8.nio.file.DirectoryStream
import java8.nio.file.FileSystemException
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.Path
import java8.nio.file.ProviderMismatchException
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributeView
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.DosFileAttributeView
import java8.nio.file.attribute.DosFileAttributes
import java8.nio.file.attribute.FileTime
import java8.nio.file.attribute.PosixFilePermissions
import me.zhanghai.android.files.provider.common.ByteStringPath
import me.zhanghai.android.files.provider.common.PosixFileAttributeView
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixGroup
import me.zhanghai.android.files.provider.common.PosixUser
import me.zhanghai.android.files.provider.common.TestPath
import me.zhanghai.android.files.provider.common.toAttribute
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.sftp.client.Authentication
import me.zhanghai.android.files.provider.sftp.client.Authenticator
import me.zhanghai.android.files.provider.sftp.client.Authority
import me.zhanghai.android.files.provider.sftp.client.Client
import me.zhanghai.android.files.provider.sftp.client.HostKeyStore
import me.zhanghai.android.files.provider.sftp.client.PasswordAuthentication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [SftpFileSystemProvider] decides before it reaches a server, and what it reports when there
 * is no server to reach.
 */
class SftpProviderOfflineTest {
    private val posixAttribute =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

    private val modeAttribute = PosixFileMode.FILE_DEFAULT.toAttribute()

    private fun <T> withFileSystem(authority: Authority, block: (SftpFileSystem) -> T): T {
        val fileSystem = SftpFileSystemProvider.getOrNewFileSystem(authority)
        try {
            return block(fileSystem)
        } finally {
            fileSystem.close()
        }
    }

    private val authority = Authority("server", Authority.DEFAULT_PORT, "user")

    private fun view(path: SftpPath, vararg options: LinkOption): PosixFileAttributeView =
        SftpFileSystemProvider.getFileAttributeView(
            path,
            PosixFileAttributeView::class.java,
            *options
        )!!

    @Test
    fun whatSftpCannotDoIsRefusedBeforeAnyServerIsAsked() {
        withFileSystem(authority) { fileSystem ->
            val file = fileSystem.getPath("/file")
            assertThrows(UnsupportedOperationException::class.java) {
                SftpFileSystemProvider.newFileChannel(file, setOf(StandardOpenOption.READ))
            }
            assertThrows(UnsupportedOperationException::class.java) {
                SftpFileSystemProvider.createLink(file, fileSystem.getPath("/other"))
            }
            assertThrows(UnsupportedOperationException::class.java) {
                SftpFileSystemProvider.createSymbolicLink(
                    file,
                    ByteStringPath("target".toByteString()),
                    posixAttribute
                )
            }
            assertThrows(ProviderMismatchException::class.java) {
                SftpFileSystemProvider.createSymbolicLink(file, TestPath("/target"))
            }
            assertThrows(UnsupportedOperationException::class.java) {
                SftpFileSystemProvider.getFileStore(file)
            }
            assertThrows(UnsupportedOperationException::class.java) {
                SftpFileSystemProvider.checkAccess(file, AccessMode.EXECUTE)
            }
            assertThrows(UnsupportedOperationException::class.java) {
                SftpFileSystemProvider.readAttributes(file, "posix:*")
            }
            assertThrows(UnsupportedOperationException::class.java) {
                SftpFileSystemProvider.setAttribute(file, "posix:permissions", emptySet<Any>())
            }
            assertThrows(UnsupportedOperationException::class.java) {
                SftpFileSystemProvider.readAttributes(file, DosFileAttributes::class.java)
            }
            assertThrows(UnsupportedOperationException::class.java) {
                SftpFileSystemProvider.newByteChannel(
                    file,
                    setOf(StandardOpenOption.WRITE, StandardOpenOption.DSYNC)
                )
            }
            assertThrows(ProviderMismatchException::class.java) {
                SftpFileSystemProvider.delete(TestPath("/file"))
            }
            // Only the app's own mode attribute can be given to what is created.
            assertThrows(UnsupportedOperationException::class.java) {
                SftpFileSystemProvider.createDirectory(file, posixAttribute)
            }
            assertThrows(UnsupportedOperationException::class.java) {
                SftpFileSystemProvider.newByteChannel(
                    file,
                    setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE),
                    posixAttribute
                )
            }
        }
    }

    @Test
    fun sameFileMeansEqualPathsAndADotMeansHidden() {
        withFileSystem(authority) { fileSystem ->
            val file = fileSystem.getPath("/dir/file")
            assertTrue(SftpFileSystemProvider.isSameFile(file, fileSystem.getPath("/dir/file")))
            assertFalse(SftpFileSystemProvider.isSameFile(file, fileSystem.getPath("/dir/other")))
            assertFalse(SftpFileSystemProvider.isSameFile(file, TestPath("/dir/file")))
            assertTrue(SftpFileSystemProvider.isHidden(fileSystem.getPath("/dir/.hidden")))
            assertFalse(SftpFileSystemProvider.isHidden(file))
            assertFalse(SftpFileSystemProvider.isHidden(fileSystem.getPath("/")))
        }
    }

    @Test
    fun theViewsAreBasicAndPosix() {
        withFileSystem(authority) { fileSystem ->
            val file = fileSystem.getPath("/file")
            assertNull(
                SftpFileSystemProvider.getFileAttributeView(file, DosFileAttributeView::class.java)
            )
            val basic =
                SftpFileSystemProvider.getFileAttributeView(
                    file,
                    BasicFileAttributeView::class.java
                )
            assertEquals("sftp", basic!!.name())
            assertEquals("sftp", view(file).name())
        }
    }

    @Test
    fun aLinkItselfCannotBeChangedAndNeitherCanItsCreationTime() {
        withFileSystem(authority) { fileSystem ->
            val link = view(fileSystem.getPath("/link"), LinkOption.NOFOLLOW_LINKS)
            val time = FileTime.fromMillis(0)
            // Setting nothing needs no server.
            link.setTimes(null, null, null)
            assertThrows(UnsupportedOperationException::class.java) {
                link.setTimes(null, null, time)
            }
            assertThrows(UnsupportedOperationException::class.java) {
                link.setTimes(time, time, null)
            }
            assertThrows(UnsupportedOperationException::class.java) {
                link.setOwner(PosixUser(0, null))
            }
            assertThrows(UnsupportedOperationException::class.java) {
                link.setGroup(PosixGroup(0, null))
            }
            assertThrows(UnsupportedOperationException::class.java) {
                link.setMode(setOf(PosixFileModeBit.OWNER_READ))
            }
            val file = view(fileSystem.getPath("/file"))
            assertThrows(UnsupportedOperationException::class.java) {
                file.setSeLinuxContext("u:object_r:file:s0".toByteString())
            }
            assertThrows(UnsupportedOperationException::class.java) {
                file.restoreSeLinuxContext()
            }
        }
    }

    @Test
    fun aServerThatIsNotThereIsAFailureAndNotAMissingOrForbiddenFile() {
        val port = ServerSocket(0).use { it.localPort }
        SftpFileSystemProvider.client = Client(
            object : Authenticator {
                override fun getAuthentication(authority: Authority): Authentication =
                    PasswordAuthentication("password")
            },
            object : HostKeyStore {
                override fun getHostKeys(host: String, port: Int): Map<String, ByteArray> =
                    emptyMap()

                override fun putHostKey(
                    host: String,
                    port: Int,
                    keyType: String,
                    key: ByteArray
                ): Unit = throw AssertionError("No host key can arrive without a server")
            }
        )
        withFileSystem(Authority("127.0.0.1", port, "user")) { fileSystem ->
            val file = fileSystem.getPath("/file")
            val time = FileTime.fromMillis(0)
            val failures = listOf(
                { SftpFileSystemProvider.delete(file) },
                { SftpFileSystemProvider.createDirectory(file) },
                { SftpFileSystemProvider.createDirectory(file, modeAttribute) },
                {
                    SftpFileSystemProvider.newByteChannel(
                        file,
                        setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE),
                        modeAttribute
                    )
                },
                { SftpFileSystemProvider.checkAccess(file, AccessMode.READ, AccessMode.WRITE) },
                { SftpFileSystemProvider.readSymbolicLink(file) },
                { SftpFileSystemProvider.createSymbolicLink(file, fileSystem.getPath("/t")) },
                { SftpFileSystemProvider.newDirectoryStream(fileSystem.getPath("/"), AcceptAll) },
                { SftpFileSystemProvider.readAttributes(file, BasicFileAttributes::class.java) },
                { SftpFileSystemProvider.newByteChannel(file, setOf(StandardOpenOption.READ)) },
                { SftpFileSystemProvider.newOutputStream(file) },
                { view(file).setTimes(time, time, null) },
                { view(file).setTimes(time, null, null) },
                { view(file).setOwner(PosixUser(0, null)) },
                { view(file).setGroup(PosixGroup(0, null)) },
                { view(file).setMode(setOf(PosixFileModeBit.OWNER_READ)) }
            ).map { operation -> assertThrows(FileSystemException::class.java) { operation() } }
            for (failure in failures) {
                assertFalse(failure.toString(), failure is NoSuchFileException)
                assertFalse(failure.toString(), failure is AccessDeniedException)
                // The failure names what was asked for: the file, or the listed directory.
                assertTrue(failure.toString(), failure.file in setOf("/file", "/"))
            }
        }
    }

    private object AcceptAll : DirectoryStream.Filter<Path> {
        override fun accept(entry: Path): Boolean = true
    }
}
