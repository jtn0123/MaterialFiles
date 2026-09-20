/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.Path
import java8.nio.file.ProviderMismatchException
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Checks that [path] belongs to the calling provider, i.e. is a [P], and smart-casts it for the
 * rest of the caller.
 *
 * @throws ProviderMismatchException if [path] is a path of another provider.
 */
@OptIn(ExperimentalContracts::class)
inline fun <reified P : Any> requireProviderPath(path: Path) {
    contract { returns() implies (path is P) }
    if (path !is P) {
        throw ProviderMismatchException(path.toString())
    }
}
