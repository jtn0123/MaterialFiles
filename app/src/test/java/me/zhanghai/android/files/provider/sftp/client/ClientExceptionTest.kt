/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import java.io.IOException
import java8.nio.file.AccessDeniedException
import java8.nio.file.DirectoryNotEmptyException
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.FileSystemLoopException
import java8.nio.file.NoSuchFileException
import java8.nio.file.NotDirectoryException
import me.zhanghai.android.files.provider.common.InvalidFileNameException
import me.zhanghai.android.files.provider.common.IsDirectoryException
import me.zhanghai.android.files.provider.common.ReadOnlyFileSystemException
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The status an SFTP server answers with has to become the matching file system exception. */
class ClientExceptionTest {
    private fun mapped(status: Response.StatusCode): FileSystemException =
        ClientException(SFTPException(status, "Server said $status"))
            .toFileSystemException("/file", "/other")

    @Test
    fun everyStatusAboutAFileBecomesItsOwnException() {
        val expected = mapOf(
            Response.StatusCode.NO_SUCH_FILE to NoSuchFileException::class.java,
            Response.StatusCode.NO_SUCH_PATH to NoSuchFileException::class.java,
            Response.StatusCode.DELETE_PENDING to NoSuchFileException::class.java,
            Response.StatusCode.PERMISSION_DENIED to AccessDeniedException::class.java,
            Response.StatusCode.CANNOT_DELETE to AccessDeniedException::class.java,
            Response.StatusCode.FILE_ALREADY_EXISTS to FileAlreadyExistsException::class.java,
            Response.StatusCode.WRITE_PROTECT to ReadOnlyFileSystemException::class.java,
            Response.StatusCode.DIR_NOT_EMPTY to DirectoryNotEmptyException::class.java,
            Response.StatusCode.NOT_A_DIRECTORY to NotDirectoryException::class.java,
            Response.StatusCode.INVALID_FILENAME to InvalidFileNameException::class.java,
            Response.StatusCode.LINK_LOOP to FileSystemLoopException::class.java,
            Response.StatusCode.FILE_IS_A_DIRECTORY to IsDirectoryException::class.java,
            Response.StatusCode.FAILURE to FileSystemException::class.java,
            Response.StatusCode.OP_UNSUPPORTED to FileSystemException::class.java
        )
        for ((status, exceptionClass) in expected) {
            val mapped = mapped(status)
            assertEquals(status.name, exceptionClass, mapped.javaClass)
            assertEquals(status.name, "/file", mapped.file)
        }
    }

    @Test
    fun theFilesAndTheServersWordsAreKept() {
        val mapped = mapped(Response.StatusCode.NO_SUCH_FILE)
        assertEquals("/other", mapped.otherFile)
        assertEquals(ClientException::class.java, mapped.cause!!.javaClass)
        assertEquals("Server said NO_SUCH_FILE", (mapped.cause!!.cause as SFTPException).message)
    }

    @Test
    fun aFailureThatIsNotFromTheServerIsAPlainFailure() {
        val cause = IOException("Connection refused")
        val exception = ClientException(cause)
        val mapped = exception.toFileSystemException("/file")
        assertEquals(FileSystemException::class.java, mapped.javaClass)
        assertSame(exception, mapped.cause)
        assertNull(mapped.otherFile)
        assertEquals(
            FileSystemException::class.java,
            ClientException("No authentication").toFileSystemException(null).javaClass
        )
        assertEquals(
            FileSystemException::class.java,
            ClientException().toFileSystemException("/file").javaClass
        )
    }

    @Test
    fun aHostKeyChangeIsFoundAnywhereInTheCauses() {
        val change =
            HostKeyChange("host", 22, "ssh-ed25519", "SHA256:old", "SHA256:new", byteArrayOf(1, 2))
        val refused = HostKeyChangedException(change)
        assertEquals(
            "Host key for host:22 (ssh-ed25519) has changed from SHA256:old to SHA256:new",
            refused.message
        )
        assertSame(change, refused.hostKeyChange)
        val wrapped = ClientException(IOException("Could not verify", refused))
        assertSame(change, wrapped.hostKeyChange)
        assertSame(change, wrapped.toFileSystemException("/file").hostKeyChange)
        assertNull(ClientException(IOException("Connection refused")).hostKeyChange)
    }

    @Test
    fun hostKeyChangesAreEqualByTheirContentIncludingTheKey() {
        fun change(key: ByteArray, newFingerprint: String = "SHA256:new") =
            HostKeyChange("host", 22, "ssh-ed25519", "SHA256:old", newFingerprint, key)
        assertEquals(change(byteArrayOf(1, 2)), change(byteArrayOf(1, 2)))
        assertEquals(change(byteArrayOf(1, 2)).hashCode(), change(byteArrayOf(1, 2)).hashCode())
        assertEquals(false, change(byteArrayOf(1, 2)) == change(byteArrayOf(1, 3)))
        assertEquals(false, change(byteArrayOf(1, 2)) == change(byteArrayOf(1, 2), "SHA256:x"))
        assertEquals(false, change(byteArrayOf(1, 2)).equals("host"))
    }
}
