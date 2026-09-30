/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav

import at.bitfire.dav4jvm.exception.ForbiddenException
import at.bitfire.dav4jvm.exception.UnauthorizedException
import java.net.ConnectException
import java8.nio.file.AccessDeniedException
import java8.nio.file.FileSystemException
import me.zhanghai.android.files.provider.common.AuthenticationFailedException
import me.zhanghai.android.files.provider.common.isAuthenticationFailure
import me.zhanghai.android.files.provider.webdav.client.toDavException
import me.zhanghai.android.files.util.findCauseByClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A 401 asks for other credentials and a 403 refuses the ones we have; the user fixes each apart. */
class DavExceptionExtensionsTest {
    @Test
    fun unauthorizedIsAnAuthenticationFailure() {
        val unauthorized = UnauthorizedException("401 Unauthorized")
        val mapped = unauthorized.toFileSystemException("/dav/file", "/dav/other")
        assertEquals(AuthenticationFailedException::class.java, mapped.javaClass)
        assertTrue(mapped.isAuthenticationFailure)
        assertSame(unauthorized, mapped.cause)
        assertEquals("/dav/file", mapped.file)
        assertEquals("/dav/other", mapped.otherFile)
    }

    @Test
    fun forbiddenIsAccessDeniedButNotAnAuthenticationFailure() {
        val mapped = ForbiddenException("403 Forbidden").toFileSystemException("/dav/file")
        assertEquals(AccessDeniedException::class.java, mapped.javaClass)
        assertFalse(mapped.isAuthenticationFailure)
    }

    @Test
    fun anIOExceptionKeepsItAsTheCause() {
        val refused = ConnectException("Failed to connect to /127.0.0.1:1")
        val mapped = refused.toDavException().toFileSystemException("/dav/file")
        assertEquals(FileSystemException::class.java, mapped.javaClass)
        assertSame(refused, mapped.cause)
        // Which is how a server form tells that the host is what to fix.
        assertSame(refused, mapped.findCauseByClass<ConnectException>())
        assertFalse(mapped.isAuthenticationFailure)
    }
}
