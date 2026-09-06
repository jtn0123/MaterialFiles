/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import java.io.IOException
import java.net.UnknownHostException
import java8.nio.file.AccessDeniedException
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.ReadOnlyFileSystemException
import org.junit.Assert.assertEquals
import org.junit.Test

class ThrowableUserMessageTest {
    @Test
    fun knownFileSystemErrorsMapToAStringAndKeepTheFile() {
        assertEquals(
            R.string.error_access_denied to "/storage/emulated/0/Android/data",
            AccessDeniedException("/storage/emulated/0/Android/data").toUserMessageParts()
        )
        assertEquals(
            R.string.error_file_not_found to "/a",
            NoSuchFileException("/a").toUserMessageParts()
        )
        assertEquals(
            R.string.error_read_only_file_system to "/a: EROFS",
            ReadOnlyFileSystemException("/a", null, "EROFS").toUserMessageParts()
        )
    }

    @Test
    fun fileSystemErrorWithoutAFileHasNoDetail() {
        assertEquals(
            R.string.error_access_denied to null,
            AccessDeniedException(null).toUserMessageParts()
        )
    }

    @Test
    fun unknownFileSystemErrorShowsItsFileAndReason() {
        assertEquals(
            null to "/a: Something odd",
            FileSystemException("/a", null, "Something odd").toUserMessageParts()
        )
    }

    @Test
    fun wrappedCausesAreUnwrapped() {
        val wrapped = IOException("Remote call failed", AccessDeniedException("/a"))
        assertEquals(R.string.error_access_denied to "/a", wrapped.toUserMessageParts())
        val host = IOException("Connect failed", UnknownHostException("nas.local"))
        assertEquals(R.string.error_unknown_host to "nas.local", host.toUserMessageParts())
    }

    @Test
    fun unrecognizedErrorsFallBackToTheDeepestMessageOrClassName() {
        assertEquals(
            null to "disk on fire",
            IllegalStateException("outer", RuntimeException("disk on fire")).toUserMessageParts()
        )
        assertEquals(null to "IllegalStateException", IllegalStateException().toUserMessageParts())
    }
}
