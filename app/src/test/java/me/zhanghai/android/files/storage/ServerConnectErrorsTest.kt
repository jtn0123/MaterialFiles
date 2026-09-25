/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java8.nio.file.AccessDeniedException
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import me.zhanghai.android.files.provider.common.AuthenticationFailedException
import org.junit.Assert.assertEquals
import org.junit.Test

/** A failure to connect points at the field of the server form that is likely wrong. */
class ServerConnectErrorsTest {
    @Test
    fun aRejectedLoginIsAboutTheCredentials() {
        assertEquals(
            ServerConnectErrorField.CREDENTIALS,
            AuthenticationFailedException("/share").serverConnectErrorField
        )
        // Providers wrap what they report, so the cause is looked for.
        assertEquals(
            ServerConnectErrorField.CREDENTIALS,
            IOException("list", AuthenticationFailedException("/share")).serverConnectErrorField
        )
    }

    @Test
    fun notReachingTheServerIsAboutTheHost() {
        for (cause in listOf(
            UnknownHostException("nas.local"),
            ConnectException("Connection refused"),
            NoRouteToHostException("No route to host"),
            SocketTimeoutException("connect timed out")
        )) {
            // This is how the SMB provider reports it: a plain FileSystemException around it.
            val reported = FileSystemException("/", null, cause.toString()).apply {
                initCause(cause)
            }
            assertEquals(
                cause.toString(),
                ServerConnectErrorField.HOST,
                reported.serverConnectErrorField
            )
        }
    }

    @Test
    fun anythingElseIsNotAboutAField() {
        for (throwable in listOf(
            AccessDeniedException("/share"),
            NoSuchFileException("/share"),
            IOException("Server went away"),
            IllegalStateException()
        )) {
            assertEquals(
                throwable.toString(),
                ServerConnectErrorField.NONE,
                throwable.serverConnectErrorField
            )
        }
    }
}
