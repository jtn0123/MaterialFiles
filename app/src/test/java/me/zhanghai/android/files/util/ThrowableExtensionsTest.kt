/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ThrowableExtensionsTest {
    @Test
    fun theThrowableItselfIsItsFirstCause() {
        val exception = FileNotFoundException("missing")

        assertSame(exception, exception.findCauseByClass<IOException>())
    }

    @Test
    fun theNearestCauseOfTheClassIsFound() {
        val nearest = FileNotFoundException("nearest")
        val exception = RuntimeException(IllegalStateException(nearest))
        nearest.initCause(IOException("farther"))

        assertSame(nearest, exception.findCauseByClass<IOException>())
    }

    @Test
    fun nothingIsFoundWithoutACauseOfTheClass() {
        val exception = RuntimeException(IllegalStateException("cause"))

        assertNull(exception.findCauseByClass<IOException>())
    }
}
