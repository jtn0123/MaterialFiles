/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.Path
import java8.nio.file.ProviderMismatchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RequireProviderPathTest {
    @Test
    fun ownPathPassesAndIsSmartCast() {
        val path: Path = TestPath("/pictures/holiday.jpg")
        requireProviderPath<TestPath>(path)
        // Only compiles because the contract smart-casts path to TestPath here.
        assertEquals("holiday.jpg".toByteString(), path.fileName!!.toByteString())
    }

    @Test
    fun foreignPathThrowsProviderMismatchNamingThePath() {
        val path: Path = TestPath("/pictures/holiday.jpg")
        try {
            requireProviderPath<ByteStringPath>(path)
            fail("Expected a ProviderMismatchException")
        } catch (e: ProviderMismatchException) {
            assertEquals("/pictures/holiday.jpg", e.message)
        }
    }

    @Test
    fun aPathIsAcceptedForASupertypeOfItsOwnType() {
        val path: Path = TestPath("relative")
        requireProviderPath<ByteStringListPath<*>>(path)
        assertTrue(path.toByteString().toString().isNotEmpty())
    }
}
