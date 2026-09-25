/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import me.zhanghai.android.files.provider.common.isAuthenticationFailure
import me.zhanghai.android.files.util.findCauseByClass

/** The field of a server form that a failure to connect to the server is about. */
enum class ServerConnectErrorField {
    /** The server could not be found or reached, so the host (or its port) is likely wrong. */
    HOST,

    /** The server turned away the credentials. */
    CREDENTIALS,

    /** Nothing the form says is known to be wrong, e.g. the server failed on its own. */
    NONE
}

/** The field of a server form that this failure to connect is about. */
val Throwable.serverConnectErrorField: ServerConnectErrorField
    get() = when {
        isAuthenticationFailure -> ServerConnectErrorField.CREDENTIALS

        findCauseByClass<UnknownHostException>() != null ||
            findCauseByClass<ConnectException>() != null ||
            findCauseByClass<NoRouteToHostException>() != null ||
            findCauseByClass<SocketTimeoutException>() != null -> ServerConnectErrorField.HOST

        else -> ServerConnectErrorField.NONE
    }
