/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.content.pm.ActivityInfo
import androidx.media3.ui.AspectRatioFrameLayout
import me.zhanghai.android.files.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** The screen orientation and resize mode the user picks in the video viewer. */
class VideoViewerDisplayModeTest {
    @Test
    fun aNewViewerFitsTheVideoAndFollowsTheSensor() {
        val displayMode = VideoViewerDisplayMode()

        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, displayMode.resizeMode)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, displayMode.screenOrientation)
    }

    @Test
    fun theFullscreenButtonSwitchesBetweenFittingAndFillingTheScreen() {
        val displayMode = VideoViewerDisplayMode()

        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, displayMode.toggleResizeMode())
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, displayMode.resizeMode)
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, displayMode.toggleResizeMode())
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, displayMode.resizeMode)
    }

    @Test
    fun screenOrientationsCycleThroughLandscapeAndPortraitBackToAuto() {
        val displayMode = VideoViewerDisplayMode()

        assertEquals(
            R.string.video_viewer_screen_orientation_landscape,
            displayMode.cycleScreenOrientation()
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            displayMode.screenOrientation
        )
        assertEquals(
            R.string.video_viewer_screen_orientation_portrait,
            displayMode.cycleScreenOrientation()
        )
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, displayMode.screenOrientation)
        assertEquals(
            R.string.video_viewer_screen_orientation_auto,
            displayMode.cycleScreenOrientation()
        )
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, displayMode.screenOrientation)
        assertEquals(0, displayMode.screenOrientationIndex)
    }

    @Test
    fun aRestoredViewerKeepsWhatWasPicked() {
        val displayMode = VideoViewerDisplayMode(2, AspectRatioFrameLayout.RESIZE_MODE_ZOOM)

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, displayMode.screenOrientation)
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, displayMode.toggleResizeMode())
    }
}
