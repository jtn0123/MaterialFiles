package me.zhanghai.android.files.provider.webdav

import at.bitfire.dav4jvm.exception.ConflictException
import at.bitfire.dav4jvm.exception.DavException
import at.bitfire.dav4jvm.exception.ForbiddenException
import at.bitfire.dav4jvm.exception.NotFoundException
import at.bitfire.dav4jvm.exception.UnauthorizedException
import java.io.IOException
import java.io.OutputStream
import java8.nio.file.AccessDeniedException
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import me.zhanghai.android.files.provider.common.AuthenticationFailedException
import me.zhanghai.android.files.provider.common.DelegateOutputStream
import me.zhanghai.android.files.provider.webdav.client.DavIOException

fun DavException.toFileSystemException(file: String?, other: String? = null): FileSystemException {
    return when (this) {
        is DavIOException ->
            return FileSystemException(file, other, message).apply { initCause(cause) }

        // A 401 means the server wants other credentials, a 403 that ours are fine but not
        // enough for this resource.
        is UnauthorizedException -> AuthenticationFailedException(file, other, message)

        is ForbiddenException -> AccessDeniedException(file, other, message)

        is NotFoundException -> NoSuchFileException(file, other, message)

        is ConflictException -> FileAlreadyExistsException(file, other, message)

        else -> FileSystemException(file, other, message)
    }.apply { initCause(this@toFileSystemException) }
}

/**
 * This upload, reporting what the server refused - which comes as a [DavException] from a write
 * or from [OutputStream.close], and is not an [IOException] - as the [FileSystemException] for
 * [file].
 */
fun OutputStream.mapDavExceptions(file: String): OutputStream =
    DavExceptionMappingOutputStream(this, file)

private class DavExceptionMappingOutputStream(
    outputStream: OutputStream,
    private val file: String
) : DelegateOutputStream(outputStream) {
    override fun write(b: Int) {
        mapDavException { super.write(b) }
    }

    override fun write(b: ByteArray) {
        mapDavException { super.write(b) }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        mapDavException { super.write(b, off, len) }
    }

    override fun flush() {
        mapDavException { super.flush() }
    }

    override fun close() {
        mapDavException { super.close() }
    }

    private inline fun mapDavException(block: () -> Unit) {
        try {
            block()
        } catch (e: DavException) {
            throw e.toFileSystemException(file)
        }
    }
}
