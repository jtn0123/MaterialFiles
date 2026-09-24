/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import me.zhanghai.android.files.databinding.VideoViewerFragmentBinding
import me.zhanghai.android.files.util.applySystemWindowInsetsToPadding
import me.zhanghai.android.files.util.mediumAnimTime
import me.zhanghai.android.systemuihelper.SystemUiHelper

/**
 * Sets up the app bar, the system bars and the playback controls of [VideoViewerFragment] so that
 * they show and hide together, and returns the helper that shows and hides the system bars.
 */
@UnstableApi
internal fun VideoViewerFragmentBinding.setUpChrome(
    activity: AppCompatActivity,
    resizeMode: Int,
    onFullscreenButtonClick: () -> Unit
): SystemUiHelper {
    activity.setSupportActionBar(toolbar)
    activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
    appBarLayout.applySystemWindowInsetsToPadding(left = true, top = true, right = true)
    val systemUiHelper = SystemUiHelper(
        activity,
        SystemUiHelper.LEVEL_IMMERSIVE,
        SystemUiHelper.FLAG_IMMERSIVE_STICKY
    ) { visible: Boolean ->
        appBarLayout.animate()
            .alpha(if (visible) 1f else 0f)
            .translationY(if (visible) 0f else -appBarLayout.bottom.toFloat())
            .setDuration(activity.mediumAnimTime.toLong())
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }
    // This will set up window flags.
    systemUiHelper.show()
    playerView.apply {
        this.resizeMode = resizeMode
        // The player fills the screen already, so let the button toggle filling it entirely.
        setFullscreenButtonClickListener { onFullscreenButtonClick() }
        // Keep our app bar in sync with the playback controls.
        setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE) systemUiHelper.show() else systemUiHelper.hide()
            }
        )
        // The playback controls are at the bottom, so they need to avoid the navigation bar.
        findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)
            ?.applySystemWindowInsetsToPadding(left = true, right = true, bottom = true)
    }
    return systemUiHelper
}
