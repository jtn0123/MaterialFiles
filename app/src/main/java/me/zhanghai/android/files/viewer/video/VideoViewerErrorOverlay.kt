/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import androidx.core.view.isVisible
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.VideoViewerFragmentBinding

/**
 * Says over the player why a video cannot be played, and offers to try again: a video on a server
 * often fails only because the connection dropped for a moment.
 */
internal class VideoViewerErrorOverlay(
    private val binding: VideoViewerFragmentBinding,
    private val getPlayer: () -> Player?
) {
    init {
        binding.retryButton.setOnClickListener { retry() }
    }

    fun show(error: PlaybackException, fileName: String) {
        val context = binding.root.context
        val (reasonRes, detail) =
            playbackErrorUserMessageParts(error.errorCode, error.errorCodeName, error.cause)
        val lines = listOfNotNull(
            context.getString(R.string.video_viewer_error_format, fileName),
            reasonRes?.let { context.getString(it) },
            detail?.takeIf { it.isNotBlank() }
        )
        binding.errorText.text = lines.joinToString("\n")
        binding.errorLayout.isVisible = true
    }

    fun hide() {
        binding.errorLayout.isVisible = false
    }

    private fun retry() {
        val player = getPlayer() ?: return
        hide()
        // A player stopped by an error keeps its playlist and position, and preparing it again
        // resumes from there.
        player.prepare()
    }
}
