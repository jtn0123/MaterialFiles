/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.CopyOption
import java8.nio.file.LinkOption
import java8.nio.file.StandardCopyOption

/**
 * The `CopyOption`s of a copy or move, unpacked once so that providers test booleans instead of
 * scanning the array. Built by `Array<CopyOption>.toCopyOptions()` and turned back with
 * [toArray] when a provider delegates to another (see `ForeignCopyMove`).
 *
 * @property replaceExisting `REPLACE_EXISTING`: an existing target is replaced instead of raising
 * `FileAlreadyExistsException`. Providers write the replacement beside the target and rename it
 * over the original only when complete.
 * @property copyAttributes `COPY_ATTRIBUTES`: also copy access and creation times, ownership and
 * mode where the target supports them; failures there are logged, never fatal.
 * @property atomicMove `ATOMIC_MOVE`: only a rename is acceptable; a copy-then-delete fallback
 * must raise `AtomicMoveNotSupportedException` instead.
 * @property noFollowLinks `NOFOLLOW_LINKS`: a symbolic link source is copied as a link.
 * @property progressListener called with the number of bytes transferred since the previous call,
 * at most every [progressIntervalMillis]; also called once with a directory's or link's size.
 */
class CopyOptions(
    val replaceExisting: Boolean,
    val copyAttributes: Boolean,
    val atomicMove: Boolean,
    val noFollowLinks: Boolean,
    val progressIntervalMillis: Long,
    val progressListener: ((Long) -> Unit)?
) {
    fun toArray(): Array<CopyOption> {
        val options = mutableListOf<CopyOption>()
        if (replaceExisting) {
            options += StandardCopyOption.REPLACE_EXISTING
        }
        if (copyAttributes) {
            options += StandardCopyOption.COPY_ATTRIBUTES
        }
        if (atomicMove) {
            options += StandardCopyOption.ATOMIC_MOVE
        }
        if (noFollowLinks) {
            options += LinkOption.NOFOLLOW_LINKS
        }
        if (progressListener != null) {
            options += ProgressCopyOption(progressIntervalMillis, progressListener)
        }
        return options.toTypedArray()
    }
}

fun Array<out CopyOption>.toCopyOptions(): CopyOptions {
    var replaceExisting = false
    var copyAttributes = false
    var atomicMove = false
    var noFollowLinks = false
    var progressIntervalMillis = 0L
    var progressListener: ((Long) -> Unit)? = null
    for (option in this) {
        when {
            option is StandardCopyOption ->
                when (option) {
                    StandardCopyOption.REPLACE_EXISTING -> replaceExisting = true
                    StandardCopyOption.COPY_ATTRIBUTES -> copyAttributes = true
                    StandardCopyOption.ATOMIC_MOVE -> atomicMove = true
                    else -> throw UnsupportedOperationException(option.toString())
                }

            option === LinkOption.NOFOLLOW_LINKS -> noFollowLinks = true

            option is ProgressCopyOption -> {
                progressIntervalMillis = option.intervalMillis
                progressListener = option.listener
            }

            else -> {
                throw UnsupportedOperationException(option.toString())
            }
        }
    }
    return CopyOptions(
        replaceExisting,
        copyAttributes,
        atomicMove,
        noFollowLinks,
        progressIntervalMillis,
        progressListener
    )
}
