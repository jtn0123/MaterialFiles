/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import androidx.annotation.StringRes
import java.net.URI
import me.zhanghai.android.files.util.takeIfNotEmpty

/**
 * The errors found in a server form, in the order its fields were checked, so that every wrong
 * field can show its error and the first one can take the focus.
 *
 * [F] identifies a field; the forms use [ServerFormField], the tests anything.
 */
class ServerFormErrors<F> {
    private val _errors = mutableListOf<Pair<F, Int>>()
    val errors: List<Pair<F, Int>>
        get() = _errors

    val isEmpty: Boolean
        get() = _errors.isEmpty()

    fun add(field: F, @StringRes error: Int) {
        _errors += field to error
    }

    /**
     * Returns the host typed in [text], bracketed if it is an IPv6 address, or null if there is
     * none. An empty or invalid host is recorded against [field].
     */
    fun checkHost(
        text: String,
        field: F,
        @StringRes emptyError: Int,
        @StringRes invalidError: Int
    ): String? {
        val host = text.takeIfNotEmpty()?.let { URI::class.canonicalizeHost(it) }
        if (host == null) {
            add(field, emptyError)
        } else if (!URI::class.isValidHost(host)) {
            add(field, invalidError)
        }
        return host
    }

    /** Returns the port typed in [text], [defaultPort] if there is none, or null if invalid. */
    fun checkPort(text: String, defaultPort: Int, field: F, @StringRes invalidError: Int): Int? {
        val port = if (text.isEmpty()) defaultPort else text.toIntOrNull()
        if (port == null) {
            add(field, invalidError)
        }
        return port
    }

    /** Returns [text], or null with [emptyError] recorded against [field] if it is empty. */
    fun checkNotEmpty(text: String, field: F, @StringRes emptyError: Int): String? {
        val value = text.takeIfNotEmpty()
        if (value == null) {
            add(field, emptyError)
        }
        return value
    }
}
