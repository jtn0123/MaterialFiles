/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

private typealias PlayerProvider = () -> ExoPlayer?

/**
 * Picture-in-picture support for [VideoViewerFragment], which creates and releases the player,
 * so [player] is looked up on every use.
 */
@UnstableApi
internal class VideoViewerPictureInPicture(
    private val activity: Activity,
    private val playerView: PlayerView,
    private val player: PlayerProvider
) {
    val isPictureInPictureSupported: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private val isInPictureInPictureMode: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            activity.isInPictureInPictureMode

    private val pictureInPictureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_PICTURE_IN_PICTURE_CONTROL) {
                return
            }
            val player = player() ?: return
            when (intent.getIntExtra(EXTRA_PICTURE_IN_PICTURE_CONTROL, 0)) {
                // Like the play button, this restarts a video that has played to the end.
                CONTROL_PLAY -> Util.handlePlayButtonAction(player)

                CONTROL_PAUSE -> player.pause()

                CONTROL_PREVIOUS -> player.seekToPreviousMediaItem()

                CONTROL_NEXT -> player.seekToNextMediaItem()
            }
        }
    }

    /** The receiver for the window's actions; keep it registered only while the fragment is. */
    fun registerReceiver() {
        ContextCompat.registerReceiver(
            activity,
            pictureInPictureReceiver,
            IntentFilter(ACTION_PICTURE_IN_PICTURE_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun unregisterReceiver() {
        activity.unregisterReceiver(pictureInPictureReceiver)
    }

    fun enterPictureInPictureMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isPictureInPictureSupported ||
            isInPictureInPictureMode
        ) {
            return
        }
        activity.enterPictureInPictureMode(createPictureInPictureParams())
    }

    /**
     * Keeps the window's aspect ratio and actions current, and on Android 12+ also whether leaving
     * the app should enter picture-in-picture, so this has to run even before we are in it.
     */
    fun updatePictureInPictureParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isPictureInPictureSupported) {
            return
        }
        activity.setPictureInPictureParams(createPictureInPictureParams())
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createPictureInPictureParams(): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .apply {
                aspectRatio()?.let { setAspectRatio(it) }
                // Lets the system animate from where the video is instead of the whole window.
                Rect().takeIf { playerView.getGlobalVisibleRect(it) }
                    ?.let { setSourceRectHint(it) }
                setActions(createPictureInPictureActions())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Unlike onUserLeaveHint(), this animates smoothly and works with gesture
                    // navigation.
                    setAutoEnterEnabled(player()?.isPlaying == true)
                }
            }
            .build()

    @RequiresApi(Build.VERSION_CODES.O)
    private fun aspectRatio(): Rational? {
        val videoSize = player()?.videoSize ?: return null
        if (videoSize.width <= 0 || videoSize.height <= 0) {
            return null
        }
        val rational = Rational(videoSize.width, videoSize.height)
        // Android rejects anything outside this range.
        return if (rational.toFloat() in ASPECT_RATIO_MIN..ASPECT_RATIO_MAX) rational else null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createPictureInPictureActions(): List<RemoteAction> {
        val player = player() ?: return emptyList()
        val actions = mutableListOf<RemoteAction>()
        if (player.hasPreviousMediaItem()) {
            actions += createPictureInPictureAction(
                CONTROL_PREVIOUS,
                androidx.media3.ui.R.drawable.exo_icon_previous,
                androidx.media3.ui.R.string.exo_controls_previous_description
            )
        }
        actions += if (player.isPlaying) {
            createPictureInPictureAction(
                CONTROL_PAUSE,
                androidx.media3.ui.R.drawable.exo_icon_pause,
                androidx.media3.ui.R.string.exo_controls_pause_description
            )
        } else {
            createPictureInPictureAction(
                CONTROL_PLAY,
                androidx.media3.ui.R.drawable.exo_icon_play,
                androidx.media3.ui.R.string.exo_controls_play_description
            )
        }
        if (player.hasNextMediaItem()) {
            actions += createPictureInPictureAction(
                CONTROL_NEXT,
                androidx.media3.ui.R.drawable.exo_icon_next,
                androidx.media3.ui.R.string.exo_controls_next_description
            )
        }
        return actions
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createPictureInPictureAction(
        control: Int,
        @DrawableRes iconRes: Int,
        @StringRes titleRes: Int
    ): RemoteAction {
        val intent = Intent(ACTION_PICTURE_IN_PICTURE_CONTROL)
            .setPackage(activity.packageName)
            .putExtra(EXTRA_PICTURE_IN_PICTURE_CONTROL, control)
        val pendingIntent = PendingIntent.getBroadcast(
            activity,
            control,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = activity.getString(titleRes)
        return RemoteAction(Icon.createWithResource(activity, iconRes), title, title, pendingIntent)
    }

    companion object {
        private const val ASPECT_RATIO_MIN = 1 / 2.39f
        private const val ASPECT_RATIO_MAX = 2.39f

        private val ACTION_PICTURE_IN_PICTURE_CONTROL =
            "${VideoViewerFragment::class.java.name}.action.PICTURE_IN_PICTURE_CONTROL"
        private val EXTRA_PICTURE_IN_PICTURE_CONTROL =
            "${VideoViewerFragment::class.java.name}.extra.PICTURE_IN_PICTURE_CONTROL"

        private const val CONTROL_PLAY = 1
        private const val CONTROL_PAUSE = 2
        private const val CONTROL_PREVIOUS = 3
        private const val CONTROL_NEXT = 4
    }
}
