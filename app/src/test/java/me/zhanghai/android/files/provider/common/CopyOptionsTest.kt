/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.CopyOption
import java8.nio.file.LinkOption
import java8.nio.file.StandardCopyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every provider reads a copy or move from these booleans, so they have to be unpacked right. */
class CopyOptionsTest {
    @Test
    fun noOptionAsksForNothing() {
        val options = emptyArray<CopyOption>().toCopyOptions()
        assertFalse(options.replaceExisting)
        assertFalse(options.copyAttributes)
        assertFalse(options.atomicMove)
        assertFalse(options.noFollowLinks)
        assertEquals(0L, options.progressIntervalMillis)
        assertNull(options.progressListener)
    }

    @Test
    fun eachOptionSetsItsOwnFlag() {
        assertTrue(arrayOf(StandardCopyOption.REPLACE_EXISTING).toCopyOptions().replaceExisting)
        assertTrue(arrayOf(StandardCopyOption.COPY_ATTRIBUTES).toCopyOptions().copyAttributes)
        assertTrue(arrayOf(StandardCopyOption.ATOMIC_MOVE).toCopyOptions().atomicMove)
        assertTrue(arrayOf<CopyOption>(LinkOption.NOFOLLOW_LINKS).toCopyOptions().noFollowLinks)
    }

    @Test
    fun theProgressListenerIsTakenWithItsInterval() {
        val listener: (Long) -> Unit = {}
        val options = arrayOf<CopyOption>(ProgressCopyOption(100, listener)).toCopyOptions()
        assertEquals(100L, options.progressIntervalMillis)
        assertSame(listener, options.progressListener)
    }

    @Test
    fun anOptionNobodyKnowsIsRefused() {
        val option = object : CopyOption {}
        assertThrows(UnsupportedOperationException::class.java) {
            arrayOf(option).toCopyOptions()
        }
    }

    @Test
    fun theOptionsCanBePassedOnToAnotherProvider() {
        val listener: (Long) -> Unit = {}
        val options = arrayOf(
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
            StandardCopyOption.ATOMIC_MOVE,
            LinkOption.NOFOLLOW_LINKS,
            ProgressCopyOption(100, listener)
        ).toCopyOptions()
        val passedOn = options.toArray().toCopyOptions()
        assertTrue(passedOn.replaceExisting)
        assertTrue(passedOn.copyAttributes)
        assertTrue(passedOn.atomicMove)
        assertTrue(passedOn.noFollowLinks)
        assertEquals(100L, passedOn.progressIntervalMillis)
        assertSame(listener, passedOn.progressListener)
    }

    @Test
    fun whatWasNotAskedForIsNotPassedOn() {
        assertEquals(0, CopyOptions(false, false, false, false, 0, null).toArray().size)
    }
}
