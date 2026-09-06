/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import java8.nio.file.Path
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

/**
 * Where playback of [paths] starts from and where it was left off, for [VideoViewerFragment],
 * which creates and releases the player, so [player] is looked up on every use.
 */
internal class VideoViewerPlaybackPosition(
    private val paths: List<Path>,
    private val player: () -> ExoPlayer?
) {
    /**
     * Where playback should start from, kept across player releases (backgrounding, and process
     * death via the fragment's saved state).
     */
    var mediaItemIndex = 0
    var positionMillis = C.TIME_UNSET

    val currentIndex: Int
        get() = player()?.currentMediaItemIndex ?: mediaItemIndex

    val currentPath: Path?
        get() = paths.getOrNull(currentIndex)

    fun savePlayerPosition() {
        val player = player() ?: return
        mediaItemIndex = player.currentMediaItemIndex
        positionMillis = player.currentPosition
    }

    /**
     * Sets the playlist, starting from where the player already is if it has one, and otherwise
     * from the saved or remembered position.
     */
    fun setMediaItems(mediaItems: List<MediaItem>) {
        val player = player() ?: return
        // We are called again once the subtitle scan finishes, and shouldn't rewind then.
        val isPlaylistSet = player.mediaItemCount > 0
        val index = if (isPlaylistSet) {
            player.currentMediaItemIndex
        } else {
            mediaItemIndex.coerceIn(0, paths.lastIndex)
        }
        val startPositionMillis = when {
            isPlaylistSet -> player.currentPosition
            positionMillis != C.TIME_UNSET -> positionMillis
            else -> rememberedPlaybackPosition(paths[index]) ?: C.TIME_UNSET
        }
        player.setMediaItems(mediaItems, index, startPositionMillis)
        player.prepare()
    }

    private fun rememberedPlaybackPosition(path: Path): Long? =
        if (Settings.VIDEO_REMEMBER_PLAYBACK_POSITION.valueCompat) {
            VideoPlaybackPositions.get(path)
        } else {
            null
        }

    fun savePlaybackPosition(index: Int, positionMillis: Long, durationMillis: Long) {
        if (!Settings.VIDEO_REMEMBER_PLAYBACK_POSITION.valueCompat) {
            return
        }
        val path = paths.getOrNull(index) ?: return
        VideoPlaybackPositions.set(path, positionMillis, durationMillis)
    }

    fun maybeResumePlaybackPosition() {
        val player = player() ?: return
        val path = currentPath ?: return
        val position = rememberedPlaybackPosition(path) ?: return
        // The player already starts at the remembered position for the initial video.
        if (player.currentPosition >= position) {
            return
        }
        player.seekTo(position)
    }

    fun playFromBeginning() {
        currentPath?.let { VideoPlaybackPositions.remove(it) }
        player()?.apply {
            seekTo(0)
            play()
        }
    }

    /** Forwarded from the fragment's [Player.Listener]. */
    fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex ||
            reason == Player.DISCONTINUITY_REASON_REMOVE
        ) {
            // Removals are handled by delete(), and paths is already updated by the time we
            // get here.
            return
        }
        // We are leaving a video, so this is our last chance to remember where we were.
        val path = paths.getOrNull(oldPosition.mediaItemIndex) ?: return
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            // It played to the end, so it should start over the next time.
            VideoPlaybackPositions.remove(path)
            return
        }
        // The player's duration is already the new video's, so ask the timeline for the old.
        val timeline = player()?.currentTimeline
        val durationMillis =
            if (timeline != null && oldPosition.mediaItemIndex < timeline.windowCount) {
                timeline.getWindow(oldPosition.mediaItemIndex, Timeline.Window()).durationMs
            } else {
                C.TIME_UNSET
            }
        savePlaybackPosition(oldPosition.mediaItemIndex, oldPosition.positionMs, durationMillis)
    }

    /** Forwarded from the fragment's [Player.Listener]. */
    fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            // Finished videos should start over the next time.
            currentPath?.let { VideoPlaybackPositions.remove(it) }
        }
    }
}
