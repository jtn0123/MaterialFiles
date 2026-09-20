/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.request.Options
import coil.size.Scale
import coil.size.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** How a decoded video frame is fitted to the size the list asked for. */
@RunWith(AndroidJUnit4::class)
class VideoFrameSizeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun options(width: Int, height: Int, allowInexactSize: Boolean): Options = Options(
        context = context,
        size = Size(width, height),
        scale = Scale.FIT,
        allowInexactSize = allowInexactSize,
        config = Bitmap.Config.ARGB_8888
    )

    @Test
    fun aFrameThatIsAlreadyTheRequestedSizeIsKept() {
        val frame = createBitmap(32, 32)

        val result = frame.toRequestedFrame(options(32, 32, false), 1.0, 32, 32)

        assertSame(frame, result)
        assertFalse(frame.isRecycled)
    }

    @Test
    fun aFrameOfTheWrongSizeIsRedrawnAndTheOriginalFreed() {
        val frame = createBitmap(64, 64).applyCanvas { drawColor(Color.GREEN) }

        val result = frame.toRequestedFrame(options(32, 32, false), 0.5, 32, 32)

        assertEquals(32, result.width)
        assertEquals(32, result.height)
        assertEquals(Color.GREEN, result.getPixel(16, 16))
        assertTrue("The frame that was redrawn should not be kept around", frame.isRecycled)
    }

    @Test
    fun aRequestThatTakesAnySizeKeepsASmallerFrame() {
        val frame = createBitmap(16, 16)

        val result = frame.toRequestedFrame(options(32, 32, true), 1.0, 16, 16)

        assertSame(frame, result)
    }
}
