/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.commit
import androidx.media3.common.util.UnstableApi
import java8.nio.file.Path
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.extraPathList
import me.zhanghai.android.files.util.getPathListExtra
import me.zhanghai.android.files.util.hasTrustedPathExtras
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.putPathListExtra

// The fragment is @UnstableApi (Media3); referencing it opts this class in too.
@UnstableApi
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
        private val EXTRA_SUBTITLE_PATH_URI_LIST =
            "${VideoViewerActivity::class.java.name}.extra.SUBTITLE_PATH_URI_LIST"

        /**
         * [subtitlePaths] are the sidecar subtitle candidates the caller already knows about
         * from listing the directory, so that the player doesn't have to list it again, which
         * matters on a remote share.
         */
        fun putExtras(
            intent: Intent,
            paths: List<Path>,
            position: Int,
            subtitlePaths: List<Path> = emptyList()
        ) {
            // All extra put here must be framework classes, or we may crash the resolver activity.
            intent.extraPathList = paths
            intent.putExtra(EXTRA_POSITION, position)
            intent.putPathListExtra(EXTRA_SUBTITLE_PATH_URI_LIST, subtitlePaths)
        }

        fun getSubtitlePathsExtra(intent: Intent): List<Path>? = if (intent.hasTrustedPathExtras) {
            intent.getPathListExtra(EXTRA_SUBTITLE_PATH_URI_LIST)
        } else {
            null
        }
    }
}
