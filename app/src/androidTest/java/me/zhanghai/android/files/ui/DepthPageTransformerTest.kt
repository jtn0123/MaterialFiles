/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The transition between two images in the image viewer. */
@RunWith(AndroidJUnit4::class)
class DepthPageTransformerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun page(): View = View(context).apply { layout(0, 0, PAGE_WIDTH, 200) }

    @Test
    fun aPageThatIsOffScreenIsInvisible() {
        val left = page()
        val right = page()

        DepthPageTransformer.transformPage(left, -2f)
        DepthPageTransformer.transformPage(right, 2f)

        assertEquals(0f, left.alpha, 0f)
        assertEquals(0f, right.alpha, 0f)
    }

    @Test
    fun thePageComingFromTheLeftJustSlides() {
        val view = page()
        view.alpha = 0f
        view.translationX = 10f
        view.scaleX = 0.5f

        DepthPageTransformer.transformPage(view, -0.5f)

        assertEquals(1f, view.alpha, 0f)
        assertEquals(0f, view.translationX, 0f)
        assertEquals(0f, view.translationZ, 0f)
        assertEquals(1f, view.scaleX, 0f)
        assertEquals(1f, view.scaleY, 0f)
    }

    @Test
    fun thePageGoingOutToTheRightStaysInPlaceWhileItFadesAndShrinks() {
        val view = page()

        DepthPageTransformer.transformPage(view, 0.5f)

        assertEquals(0.5f, view.alpha, 0f)
        // Counteracts the slide, so the page stays where it is.
        assertEquals(-PAGE_WIDTH / 2f, view.translationX, 0f)
        // Behind the page that slides in.
        assertEquals(-1f, view.translationZ, 0f)
        assertEquals(0.875f, view.scaleX, 0f)
        assertEquals(0.875f, view.scaleY, 0f)
    }

    companion object {
        private const val PAGE_WIDTH = 100
    }
}
