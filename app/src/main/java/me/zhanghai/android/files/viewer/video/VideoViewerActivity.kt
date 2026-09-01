/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.commit
import java8.nio.file.Path
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.extraPathList
import me.zhanghai.android.files.util.putArgs

class VideoViewerActivity : AppActivity() {
    private val fragment: VideoViewerFragment?
        get() = supportFragmentManager.findFragmentByTag(FRAGMENT_TAG) as VideoViewerFragment?

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            val intent = intent
            val position = intent.getIntExtra(EXTRA_POSITION, 0)
            val fragment = VideoViewerFragment()
                .putArgs(VideoViewerFragment.Args(intent, position))
            supportFragmentManager.commit {
                add(android.R.id.content, fragment, FRAGMENT_TAG)
            }
        }
    }

    // Fragment isn't notified about this, so we have to forward it ourselves.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        fragment?.onUserLeaveHint()
    }

    companion object {
        private const val FRAGMENT_TAG = "VideoViewerFragment"

        private val EXTRA_POSITION = "${VideoViewerActivity::class.java.name}.extra.POSITION"

        fun putExtras(intent: Intent, paths: List<Path>, position: Int) {
            // All extra put here must be framework classes, or we may crash the resolver activity.
            intent.extraPathList = paths
            intent.putExtra(EXTRA_POSITION, position)
        }
    }
}
