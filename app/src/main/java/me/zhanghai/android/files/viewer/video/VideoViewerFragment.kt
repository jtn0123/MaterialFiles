/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import java.io.IOException
import java8.nio.file.Path
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.VideoViewerFragmentBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.guessFromPath
import me.zhanghai.android.files.provider.common.delete
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableListParceler
import me.zhanghai.android.files.util.ParcelableState
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.autoCleared
import me.zhanghai.android.files.util.createSendStreamIntent
import me.zhanghai.android.files.util.extraPathList
import me.zhanghai.android.files.util.finish
import me.zhanghai.android.files.util.getState
import me.zhanghai.android.files.util.logWarning
import me.zhanghai.android.files.util.putState
import me.zhanghai.android.files.util.showActionSnackbar
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.toUserMessage
import me.zhanghai.android.files.util.withChooser
import me.zhanghai.android.files.viewer.image.ConfirmDeleteDialogFragment
import me.zhanghai.android.systemuihelper.SystemUiHelper

@UnstableApi
class VideoViewerFragment :
    Fragment(),
    MenuProvider,
    ConfirmDeleteDialogFragment.Listener {
    private val args by args<Args>()
    private val argsPaths by lazy { args.intent.extraPathList }

    private lateinit var paths: MutableList<Path>

    /**
     * What we deleted from [argsPaths], which is all the saved state needs to rebuild [paths]:
     * a folder of videos can be too large to save again (a binder transaction is capped at 1 MB).
     */
    private val deletedPaths = mutableListOf<Path>()

    private var binding by autoCleared<VideoViewerFragmentBinding>()

    private var pictureInPicture by autoCleared<VideoViewerPictureInPicture>()

    private var errorOverlay by autoCleared<VideoViewerErrorOverlay>()

    private lateinit var systemUiHelper: SystemUiHelper

    private var player: ExoPlayer? = null

    private var subtitlesByPath: Map<Path, List<MediaItem.SubtitleConfiguration>> = emptyMap()

    private lateinit var playbackPosition: VideoViewerPlaybackPosition

    private lateinit var displayMode: VideoViewerDisplayMode

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateTitle()
            errorOverlay.hide()
            // A (re)set playlist already starts where it should, and seeking now would jump away
            // from wherever the user has scrubbed to since.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                playbackPosition.maybeResumePlaybackPosition()
            }
            pictureInPicture.updatePictureInPictureParams()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            playbackPosition.onPositionDiscontinuity(oldPosition, newPosition, reason)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            playbackPosition.onPlaybackStateChanged(playbackState)
            // The playback controls can start a failed video again as well.
            if (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY) {
                errorOverlay.hide()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            binding.playerView.keepScreenOn = isPlaying
            pictureInPicture.updatePictureInPictureParams()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            pictureInPicture.updatePictureInPictureParams()
        }

        override fun onPlayerError(error: PlaybackException) {
            error.logWarning("VideoViewerFragment", "Play the video")
            val fileName = playbackPosition.currentPath?.fileName?.toString() ?: return
            errorOverlay.show(error, fileName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val state = savedInstanceState?.getState<State>()
        state?.deletedPaths?.let { deletedPaths += it }
        paths = argsPaths.toMutableList().apply { removeAll(deletedPaths) }
        playbackPosition = VideoViewerPlaybackPosition(paths) { player }.apply {
            mediaItemIndex = state?.mediaItemIndex
                ?: args.position.coerceIn(0, paths.lastIndex.coerceAtLeast(0))
            positionMillis = state?.positionMillis ?: C.TIME_UNSET
        }
        displayMode = VideoViewerDisplayMode(
            state?.screenOrientationIndex ?: 0,
            state?.resizeMode ?: AspectRatioFrameLayout.RESIZE_MODE_FIT
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = VideoViewerFragmentBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pictureInPicture = VideoViewerPictureInPicture(requireActivity(), binding.playerView) {
            player
        }
        errorOverlay = VideoViewerErrorOverlay(binding) { player }
        if (paths.isEmpty()) {
            finish()
            return
        }

        requireActivity().addMenuProvider(this, viewLifecycleOwner)
        systemUiHelper = binding.setUpChrome(
            activity as AppCompatActivity,
            displayMode.resizeMode
        ) { binding.playerView.resizeMode = displayMode.toggleResizeMode() }
        requireActivity().requestedOrientation = displayMode.screenOrientation
        updateTitle()

        viewLifecycleOwner.lifecycleScope.launch { loadSubtitles() }
    }

    override fun onStart() {
        super.onStart()

        pictureInPicture.registerReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            initializePlayer()
        }
    }

    override fun onResume() {
        super.onResume()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            initializePlayer()
        }
    }

    override fun onPause() {
        super.onPause()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            releasePlayer()
        }
    }

    override fun onStop() {
        super.onStop()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            releasePlayer()
        }
        pictureInPicture.unregisterReceiver()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        playbackPosition.savePlayerPosition()
        outState.putState(
            State(
                deletedPaths,
                playbackPosition.mediaItemIndex,
                playbackPosition.positionMillis,
                displayMode.screenOrientationIndex,
                displayMode.resizeMode
            )
        )
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.video_viewer, menu)
        menu.findItem(R.id.action_picture_in_picture).isVisible =
            pictureInPicture.isPictureInPictureSupported
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
        R.id.action_picture_in_picture -> {
            pictureInPicture.enterPictureInPictureMode()
            true
        }

        R.id.action_screen_orientation -> {
            val title = displayMode.cycleScreenOrientation()
            requireActivity().requestedOrientation = displayMode.screenOrientation
            showToast(getString(title))
            true
        }

        R.id.action_play_from_beginning -> {
            playbackPosition.playFromBeginning()
            true
        }

        R.id.action_delete -> {
            confirmDelete()
            true
        }

        R.id.action_share -> {
            share()
            true
        }

        else -> false
    }

    private suspend fun loadSubtitles() {
        subtitlesByPath =
            VideoSubtitles.load(paths, VideoViewerActivity.getSubtitlePathsExtra(args.intent))
        // Re-preparing the player interrupts playback, so only do it if we found anything.
        if (subtitlesByPath.values.any { it.isNotEmpty() }) {
            setMediaItems()
        }
    }

    private fun initializePlayer() {
        // We are finishing when we have nothing to play.
        if (player != null || paths.isEmpty()) {
            return
        }
        val player = ExoPlayer.Builder(requireContext())
            .build()
            .apply {
                setAudioAttributes(AudioAttributes.DEFAULT, true)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_LOCAL)
                addListener(playerListener)
                playWhenReady = true
            }
        this.player = player
        binding.playerView.player = player
        // A new player tries the video again.
        errorOverlay.hide()
        setMediaItems()
    }

    /**
     * (Re-)fills the playlist, which happens both when the player is created and when the subtitle
     * scan finishes.
     */
    private fun setMediaItems() {
        val mediaItems = paths.map { path ->
            MediaItem.Builder()
                .setMediaId(path.toUri().toString())
                .setUri(path.fileProviderUri)
                .setSubtitleConfigurations(subtitlesByPath[path] ?: emptyList())
                .build()
        }
        playbackPosition.setMediaItems(mediaItems)
    }

    private fun releasePlayer() {
        val player = player ?: return
        playbackPosition.savePlayerPosition()
        playbackPosition.savePlaybackPosition(
            player.currentMediaItemIndex,
            player.currentPosition,
            player.duration
        )
        player.removeListener(playerListener)
        binding.playerView.player = null
        player.release()
        this.player = null
        pictureInPicture.updatePictureInPictureParams()
    }

    fun onUserLeaveHint() {
        // Android 12+ enters picture-in-picture for us, see VideoViewerPictureInPicture.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && player?.isPlaying == true) {
            pictureInPicture.enterPictureInPictureMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)

        if (view == null) {
            return
        }
        binding.playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            binding.playerView.hideController()
            systemUiHelper.hide()
        }
    }

    private fun confirmDelete() {
        val path = playbackPosition.currentPath ?: return
        ConfirmDeleteDialogFragment.show(path, this)
    }

    override fun delete(path: Path) {
        try {
            path.delete()
        } catch (e: IOException) {
            e.logWarning("VideoViewerFragment", "Delete $path")
            binding.root.showActionSnackbar(e.toUserMessage(requireContext()), R.string.retry) {
                delete(path)
            }
            return
        }
        VideoPlaybackPositions.remove(path)
        deletedPaths.add(path)
        val index = paths.indexOf(path)
        paths.removeAll(listOf(path))
        if (paths.isEmpty()) {
            finish()
            return
        }
        if (index != -1) {
            val player = player
            val wasCurrent = player?.currentMediaItemIndex == index
            player?.removeMediaItem(index)
            if (wasCurrent) {
                // We moved on to another video, and playlist changes don't seek by themselves.
                playbackPosition.maybeResumePlaybackPosition()
            }
        }
        updateTitle()
    }

    private fun share() {
        val path = playbackPosition.currentPath ?: return
        val mimeType = MimeType.guessFromPath(path.toString())
        val intent = path.fileProviderUri.createSendStreamIntent(mimeType).withChooser()
        startActivitySafe(intent)
    }

    private fun updateTitle() {
        val path = playbackPosition.currentPath ?: return
        requireActivity().title = path.fileName.toString()
        val size = paths.size
        binding.toolbar.subtitle = if (size > 1) {
            getString(
                R.string.video_viewer_subtitle_format,
                playbackPosition.currentIndex + 1,
                size
            )
        } else {
            null
        }
    }

    @Parcelize
    class Args(val intent: Intent, val position: Int) : ParcelableArgs

    @Parcelize
    private class State(
        val deletedPaths: @WriteWith<ParcelableListParceler> List<Path>,
        val mediaItemIndex: Int,
        val positionMillis: Long,
        val screenOrientationIndex: Int,
        val resizeMode: Int
    ) : ParcelableState
}
