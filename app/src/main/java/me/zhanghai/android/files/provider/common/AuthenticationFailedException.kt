/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.AccessDeniedException
import me.zhanghai.android.files.util.findCauseByClass

/**
 * The server turned away the credentials we signed in with, as opposed to refusing access to a
 * file we were signed in for.
 *
 * It is still an [AccessDeniedException], so that everything handling one keeps doing so; only what
 * is shown to the user tells the two apart, since a wrong password is fixed by editing the server
 * and not by asking whoever owns the file.
 */
class AuthenticationFailedException : AccessDeniedException {
    constructor(file: String?) : super(file)

    constructor(file: String?, other: String?, reason: String?) : super(file, other, reason)
}

/** Whether this, or any of its causes, is the server turning away our credentials. */
val Throwable.isAuthenticationFailure: Boolean
    get() = findCauseByClass<AuthenticationFailedException>() != null
