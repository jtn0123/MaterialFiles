/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb.client

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import java.io.IOException
import java8.nio.file.AccessDeniedException
import java8.nio.file.AtomicMoveNotSupportedException
import java8.nio.file.DirectoryNotEmptyException
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import java8.nio.file.NotDirectoryException
import java8.nio.file.NotLinkException
import me.zhanghai.android.files.provider.common.InvalidFileNameException
import me.zhanghai.android.files.provider.common.IsDirectoryException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** The NT status a server answers with has to become the matching file system exception. */
class ClientExceptionTest {
    private fun exception(status: Long) =
        ClientException(SMBApiException(status, SMB2MessageCommandCode.SMB2_CREATE, null))

    private fun exception(status: NtStatus) = exception(status.value)

    private fun mapped(status: NtStatus): FileSystemException =
        exception(status).toFileSystemException("/share/file", "/share/other")

    @Test
    fun deniedAndLockedOutStatusesAreAccessDenied() {
        for (status in listOf(
            NtStatus.STATUS_ACCESS_DENIED,
            NtStatus.STATUS_SHARING_VIOLATION,
            NtStatus.STATUS_PRIVILEGE_NOT_HELD,
            NtStatus.STATUS_LOGON_FAILURE,
            NtStatus.STATUS_PASSWORD_EXPIRED,
            NtStatus.STATUS_ACCOUNT_DISABLED,
            NtStatus.STATUS_OPLOCK_NOT_GRANTED,
            NtStatus.STATUS_CANNOT_DELETE,
            NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
            NtStatus.STATUS_FILE_ENCRYPTED
        )) {
            assertEquals(status.name, AccessDeniedException::class.java, mapped(status).javaClass)
        }
    }

    @Test
    fun everyWayOfSayingThatSomethingIsNotThereIsNoSuchFile() {
        for (status in listOf(
            NtStatus.STATUS_NO_SUCH_FILE,
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_DELETE_PENDING,
            NtStatus.STATUS_BAD_NETWORK_PATH,
            NtStatus.STATUS_BAD_NETWORK_NAME,
            NtStatus.STATUS_NOT_FOUND
        )) {
            val mapped = mapped(status)
            assertEquals(status.name, NoSuchFileException::class.java, mapped.javaClass)
            assertEquals("/share/file", mapped.file)
            assertEquals("/share/other", mapped.otherFile)
        }
    }

    @Test
    fun theOtherStatusesAboutAFileBecomeTheirOwnExceptions() {
        assertEquals(
            FileAlreadyExistsException::class.java,
            mapped(NtStatus.STATUS_OBJECT_NAME_COLLISION).javaClass
        )
        assertEquals(
            IsDirectoryException::class.java,
            mapped(NtStatus.STATUS_FILE_IS_A_DIRECTORY).javaClass
        )
        assertEquals(
            NotDirectoryException::class.java,
            mapped(NtStatus.STATUS_NOT_A_DIRECTORY).javaClass
        )
        assertEquals(
            DirectoryNotEmptyException::class.java,
            mapped(NtStatus.STATUS_DIRECTORY_NOT_EMPTY).javaClass
        )
    }

    @Test
    fun aReparsePointThatIsNotALinkIsNotALink() {
        for (status in listOf(
            NtStatuses.STATUS_NOT_A_REPARSE_POINT,
            NtStatuses.STATUS_IO_REPARSE_TAG_INVALID,
            NtStatuses.STATUS_IO_REPARSE_TAG_MISMATCH,
            NtStatus.STATUS_IO_REPARSE_TAG_NOT_HANDLED.value
        )) {
            val mapped = exception(status).toFileSystemException("/share/link")
            assertEquals(status.toString(16), NotLinkException::class.java, mapped.javaClass)
        }
    }

    @Test
    fun anythingElseIsAPlainFailureThatKeepsItsCause() {
        val statusException = exception(NtStatus.STATUS_DISK_FULL)
        val mapped = statusException.toFileSystemException("/share/file")
        assertEquals(FileSystemException::class.java, mapped.javaClass)
        assertSame(statusException, mapped.cause)
        assertEquals(statusException.message, mapped.reason)

        val ioException = ClientException(IOException("Connection reset"))
        val wrapped = ioException.toFileSystemException("/share/file")
        assertEquals(FileSystemException::class.java, wrapped.javaClass)
        assertSame(ioException, wrapped.cause)

        val noCause = ClientException("No password found").toFileSystemException(null)
        assertEquals(FileSystemException::class.java, noCause.javaClass)
        assertEquals("No password found", noCause.reason)
    }

    @Test
    fun aMoveAcrossDevicesIsAnAtomicMoveThatIsNotSupported() {
        val moveException = exception(NtStatus.STATUS_NOT_SAME_DEVICE)
        val thrown = assertThrows(AtomicMoveNotSupportedException::class.java) {
            moveException.maybeThrowAtomicMoveNotSupportedException("/share/a", "/other/b")
        }
        assertEquals("/share/a", thrown.file)
        assertEquals("/other/b", thrown.otherFile)
        assertSame(moveException, thrown.cause)
        // Any other status is left for the caller to map.
        exception(NtStatus.STATUS_ACCESS_DENIED)
            .maybeThrowAtomicMoveNotSupportedException("/share/a", "/other/b")
        ClientException().maybeThrowAtomicMoveNotSupportedException("/share/a", "/other/b")
    }

    @Test
    fun anInvalidNameIsAnInvalidFileName() {
        val nameException = exception(NtStatus.STATUS_OBJECT_NAME_INVALID)
        val thrown = assertThrows(InvalidFileNameException::class.java) {
            nameException.maybeThrowInvalidFileNameException("/share/a:b")
        }
        assertEquals("/share/a:b", thrown.file)
        assertSame(nameException, thrown.cause)
        exception(NtStatus.STATUS_ACCESS_DENIED).maybeThrowInvalidFileNameException("/share/a")
        ClientException("message", IOException()).maybeThrowInvalidFileNameException("/share/a")
    }
}
