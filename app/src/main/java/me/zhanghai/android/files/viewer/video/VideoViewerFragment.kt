/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
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
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
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
import androidx.media3.ui.PlayerView
import java.io.IOException
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.VideoViewerFragmentBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.guessFromPath
import me.zhanghai.android.files.filelist.isRemotePath
import me.zhanghai.android.files.provider.common.delete
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableListParceler
import me.zhanghai.android.files.util.ParcelableState
import me.zhanghai.android.files.util.applySystemWindowInsetsToPadding
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.autoCleared
import me.zhanghai.android.files.util.createSendStreamIntent
import me.zhanghai.android.files.util.extraPathList
import me.zhanghai.android.files.util.finish
import me.zhanghai.android.files.util.getState
import me.zhanghai.android.files.util.mediumAnimTime
import me.zhanghai.android.files.util.putState
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

    private lateinit var systemUiHelper: SystemUiHelper

    private var player: ExoPlayer? = null

    private var subtitlesByPath: Map<Path, List<MediaItem.SubtitleConfiguration>> = emptyMap()

    private lateinit var playbackPosition: VideoViewerPlaybackPosition

    private var screenOrientationIndex = 0
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateTitle()
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
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            binding.playerView.keepScreenOn = isPlaying
            pictureInPicture.updatePictureInPictureParams()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            pictureInPicture.updatePictureInPictureParams()
        }

        override fun onPlayerError(error: PlaybackException) {
            error.printStackTrace()
            val fileName = playbackPosition.currentPath?.fileName?.toString() ?: return
            showToast(getString(R.string.video_viewer_error_format, fileName))
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
        screenOrientationIndex = state?.screenOrientationIndex ?: 0
        resizeMode = state?.resizeMode ?: AspectRatioFrameLayout.RESIZE_MODE_FIT
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
        if (paths.isEmpty()) {
            finish()
            return
        }

        requireActivity().addMenuProvider(this, viewLifecycleOwner)
        val activity = activity as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        // Our app bar will draw the status bar background.
        activity.window.statusBarColor = Color.TRANSPARENT
        binding.appBarLayout.applySystemWindowInsetsToPadding(left = true, top = true, right = true)
        systemUiHelper = SystemUiHelper(
            activity,
            SystemUiHelper.LEVEL_IMMERSIVE,
            SystemUiHelper.FLAG_IMMERSIVE_STICKY
        ) { visible: Boolean ->
            binding.appBarLayout.animate()
                .alpha(if (visible) 1f else 0f)
                .translationY(if (visible) 0f else -binding.appBarLayout.bottom.toFloat())
                .setDuration(mediumAnimTime.toLong())
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
        }
        // This will set up window flags.
        systemUiHelper.show()
        binding.playerView.apply {
            resizeMode = this@VideoViewerFragment.resizeMode
            // The player fills the screen already, so let the button toggle filling it entirely.
            setFullscreenButtonClickListener { toggleResizeMode() }
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
        applyScreenOrientation()
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
                screenOrientationIndex,
                resizeMode
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
            cycleScreenOrientation()
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
        val knownSubtitlePaths = VideoViewerActivity.getSubtitlePathsExtra(args.intent)
        subtitlesByPath = when {
            // Our file list already listed the directory and told us what it found.
            knownSubtitlePaths != null -> VideoSubtitles.findForAll(paths, knownSubtitlePaths)

            // Listing a remote directory just for subtitles is slower than it is worth, so only
            // scan when we were opened from elsewhere with local files.
            paths.any { it.isRemotePath } -> emptyMap()

            else -> withTimeoutOrNull(SUBTITLE_TIMEOUT_MILLIS) {
                runInterruptible(Dispatchers.IO) { VideoSubtitles.findForAll(paths) }
            } ?: emptyMap()
        }
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

    private fun toggleResizeMode() {
        resizeMode = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        binding.playerView.resizeMode = resizeMode
    }

    private fun cycleScreenOrientation() {
        screenOrientationIndex = (screenOrientationIndex + 1) % SCREEN_ORIENTATIONS.size
        applyScreenOrientation()
        showToast(getString(SCREEN_ORIENTATION_TITLE_RESOURCES[screenOrientationIndex]))
    }

    private fun applyScreenOrientation() {
        requireActivity().requestedOrientation = SCREEN_ORIENTATIONS[screenOrientationIndex]
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
            e.printStackTrace()
            showToast(e.toUserMessage(requireContext()))
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

    companion object {
        private const val SUBTITLE_TIMEOUT_MILLIS = 5_000L

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
