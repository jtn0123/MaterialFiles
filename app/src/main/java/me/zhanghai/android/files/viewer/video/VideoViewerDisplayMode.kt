/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.content.pm.ActivityInfo
import androidx.annotation.StringRes
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import me.zhanghai.android.files.R

/**
 * How the video viewer lays out the video: the screen orientation the user picked and whether the
 * video fits the screen or fills it.
 */
@UnstableApi
internal class VideoViewerDisplayMode(
    var screenOrientationIndex: Int = 0,
    var resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT
) {
    /** The screen orientation to request from the activity. */
    val screenOrientation: Int
        get() = SCREEN_ORIENTATIONS[screenOrientationIndex]

    /** Switches between fitting and filling the screen and returns the new resize mode. */
    fun toggleResizeMode(): Int {
        resizeMode = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        return resizeMode
    }

    /** Moves on to the next screen orientation and returns its title. */
    @StringRes
    fun cycleScreenOrientation(): Int {
        screenOrientationIndex = (screenOrientationIndex + 1) % SCREEN_ORIENTATIONS.size
        return SCREEN_ORIENTATION_TITLE_RESOURCES[screenOrientationIndex]
    }

    companion object {
        private val SCREEN_ORIENTATIONS = intArrayOf(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        )

        private val SCREEN_ORIENTATION_TITLE_RESOURCES = intArrayOf(
            R.string.video_viewer_screen_orientation_auto,
            R.string.video_viewer_screen_orientation_landscape,
            R.string.video_viewer_screen_orientation_portrait
        )
    }
}
