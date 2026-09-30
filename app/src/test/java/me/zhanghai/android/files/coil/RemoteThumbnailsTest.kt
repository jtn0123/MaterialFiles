/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteThumbnailsTest {
    @Test
    fun aListIconAndSizesAPixelApartShareOneSize() {
        assertEquals(256, RemoteThumbnails.roundSize(1))
        assertEquals(256, RemoteThumbnails.roundSize(140))
        assertEquals(256, RemoteThumbnails.roundSize(256))
        assertEquals(768, RemoteThumbnails.roundSize(607))
        assertEquals(768, RemoteThumbnails.roundSize(600))
    }

    @Test
    fun aThumbnailIsNeverRoundedPastTheLargestKept() {
        assertEquals(1024, RemoteThumbnails.roundSize(1000))
        assertEquals(1024, RemoteThumbnails.roundSize(1024))
    }

    @Test
    fun aSizeForAViewerIsLeftAsItIs() {
        assertEquals(1025, RemoteThumbnails.roundSize(1025))
        assertEquals(3120, RemoteThumbnails.roundSize(3120))
    }

    @Test
    fun everySizeKeptOnDiskIsTriedLargestFirst() {
        val sizes = RemoteThumbnails.cachedSizesLargestFirst
        assertEquals(16, sizes.size)
        assertEquals(16, sizes.toSet().size)
        assertEquals(1024 to 1024, sizes.first())
        assertEquals(256 to 256, sizes.last())
        assertTrue(
            sizes.zipWithNext().all { (a, b) -> a.first * a.second >= b.first * b.second }
        )
        // Each is a size a thumbnail can be rounded to, and so kept at.
        assertTrue(
            sizes.all { (width, height) ->
                RemoteThumbnails.roundSize(width) == width &&
                    RemoteThumbnails.roundSize(height) == height
            }
        )
    }

    @Test
    fun aFailureIsRememberedUntilItExpires() {
        var now = 0L
        val failures = RecentFailures(maxCount = 10, expiryMillis = 1000, clock = { now })
        failures.add("a")
        now = 999
        assertTrue("a" in failures)
        now = 1000
        assertFalse("a" in failures)
        assertFalse("b" in failures)
    }

    @Test
    fun theLeastRecentlyAskedAboutIsForgottenFirst() {
        val failures = RecentFailures(maxCount = 2, expiryMillis = 1000, clock = { 0L })
        failures.add("a")
        failures.add("b")
        assertTrue("a" in failures)
        failures.add("c")
        assertTrue("a" in failures)
        assertFalse("b" in failures)
        assertTrue("c" in failures)
    }
}
