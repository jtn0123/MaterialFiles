/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.chrisbanes.insetter.applySystemWindowInsetsToPadding
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
import me.zhanghai.android.files.provider.common.delete
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableListParceler
import me.zhanghai.android.files.util.ParcelableState
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.autoCleared
import me.zhanghai.android.files.util.createSendStreamIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.extraPathList
import me.zhanghai.android.files.util.finish
import me.zhanghai.android.files.util.getState
import me.zhanghai.android.files.util.mediumAnimTime
import me.zhanghai.android.files.util.putState
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.valueCompat
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

    private var binding by autoCleared<VideoViewerFragmentBinding>()

    private lateinit var systemUiHelper: SystemUiHelper

    private var player: ExoPlayer? = null

    private var subtitlesByPath: Map<Path, List<MediaItem.SubtitleConfiguration>> = emptyMap()

    /**
     * Where playback should start from, kept across player releases (backgrounding, and process
     * death via [State]).
     */
    private var mediaItemIndex = 0
    private var positionMillis = C.TIME_UNSET

    private var screenOrientationIndex = 0
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    private val isPictureInPictureSupported: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            requireContext().packageManager
                .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private val isInPictureInPictureMode: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            requireActivity().isInPictureInPictureMode

    private val pictureInPictureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_PICTURE_IN_PICTURE_CONTROL) {
                return
            }
            val player = player ?: return
            when (intent.getIntExtra(EXTRA_PICTURE_IN_PICTURE_CONTROL, 0)) {
                // Like the play button, this restarts a video that has played to the end.
                CONTROL_PLAY -> Util.handlePlayButtonAction(player)

                CONTROL_PAUSE -> player.pause()

                CONTROL_PREVIOUS -> player.seekToPreviousMediaItem()

                CONTROL_NEXT -> player.seekToNextMediaItem()
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateTitle()
            // A (re)set playlist already starts where it should, and seeking now would jump away
            // from wherever the user has scrubbed to since.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                maybeResumePlaybackPosition()
            }
            updatePictureInPictureParams()
        }

        override fun onPositionDiscontinuity(
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
            val timeline = player?.currentTimeline
            val durationMillis =
                if (timeline != null && oldPosition.mediaItemIndex < timeline.windowCount) {
                    timeline.getWindow(oldPosition.mediaItemIndex, Timeline.Window()).durationMs
                } else {
                    C.TIME_UNSET
                }
            savePlaybackPosition(oldPosition.mediaItemIndex, oldPosition.positionMs, durationMillis)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                // Finished videos should start over the next time.
                currentPath?.let { VideoPlaybackPositions.remove(it) }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            binding.playerView.keepScreenOn = isPlaying
            updatePictureInPictureParams()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updatePictureInPictureParams()
        }

        override fun onPlayerError(error: PlaybackException) {
            error.printStackTrace()
            val fileName = currentPath?.fileName?.toString() ?: return
            showToast(getString(R.string.video_viewer_error_format, fileName))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val state = savedInstanceState?.getState<State>()
        paths = (state?.paths ?: argsPaths).toMutableList()
        mediaItemIndex = state?.mediaItemIndex
            ?: args.position.coerceIn(0, paths.lastIndex.coerceAtLeast(0))
        positionMillis = state?.positionMillis ?: C.TIME_UNSET
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

        ContextCompat.registerReceiver(
            requireContext(),
            pictureInPictureReceiver,
            IntentFilter(ACTION_PICTURE_IN_PICTURE_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
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
        requireContext().unregisterReceiver(pictureInPictureReceiver)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        savePlayerPosition()
        outState.putState(
            State(paths, mediaItemIndex, positionMillis, screenOrientationIndex, resizeMode)
        )
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.video_viewer, menu)
        menu.findItem(R.id.action_picture_in_picture).isVisible = isPictureInPictureSupported
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
        R.id.action_picture_in_picture -> {
            enterPictureInPictureMode()
            true
        }

        R.id.action_screen_orientation -> {
            cycleScreenOrientation()
            true
        }

        R.id.action_play_from_beginning -> {
            playFromBeginning()
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
        // A remote directory can be slow to list, and subtitles aren't worth waiting long for.
        subtitlesByPath = withTimeoutOrNull(SUBTITLE_TIMEOUT_MILLIS) {
            runInterruptible(Dispatchers.IO) { VideoSubtitles.findForAll(paths) }
        } ?: emptyMap()
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
        val player = player ?: return
        val mediaItems = paths.map { path ->
            MediaItem.Builder()
                .setMediaId(path.toUri().toString())
                .setUri(path.fileProviderUri)
                .setSubtitleConfigurations(subtitlesByPath[path] ?: emptyList())
                .build()
        }
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

    private fun releasePlayer() {
        val player = player ?: return
        savePlayerPosition()
        savePlaybackPosition(player.currentMediaItemIndex, player.currentPosition, player.duration)
        player.removeListener(playerListener)
        binding.playerView.player = null
        player.release()
        this.player = null
        updatePictureInPictureParams()
    }

    private fun savePlayerPosition() {
        val player = player ?: return
        mediaItemIndex = player.currentMediaItemIndex
        positionMillis = player.currentPosition
    }

    private fun rememberedPlaybackPosition(path: Path): Long? =
        if (Settings.VIDEO_REMEMBER_PLAYBACK_POSITION.valueCompat) {
            VideoPlaybackPositions.get(path)
        } else {
            null
        }

    private fun savePlaybackPosition(index: Int, positionMillis: Long, durationMillis: Long) {
        if (!Settings.VIDEO_REMEMBER_PLAYBACK_POSITION.valueCompat) {
            return
        }
        val path = paths.getOrNull(index) ?: return
        VideoPlaybackPositions.set(path, positionMillis, durationMillis)
    }

    private fun maybeResumePlaybackPosition() {
        val player = player ?: return
        val path = currentPath ?: return
        val position = rememberedPlaybackPosition(path) ?: return
        // The player already starts at the remembered position for the initial video.
        if (player.currentPosition >= position) {
            return
        }
        player.seekTo(position)
    }

    private fun playFromBeginning() {
        currentPath?.let { VideoPlaybackPositions.remove(it) }
        player?.apply {
            seekTo(0)
            play()
        }
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
        // Android 12+ enters picture-in-picture for us, see createPictureInPictureParams().
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && player?.isPlaying == true) {
            enterPictureInPictureMode()
        }
    }

    private fun enterPictureInPictureMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isPictureInPictureSupported ||
            isInPictureInPictureMode
        ) {
            return
        }
        requireActivity().enterPictureInPictureMode(createPictureInPictureParams())
    }

    /**
     * Keeps the window's aspect ratio and actions current, and on Android 12+ also whether leaving
     * the app should enter picture-in-picture, so this has to run even before we are in it.
     */
    private fun updatePictureInPictureParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isPictureInPictureSupported) {
            return
        }
        val activity = activity ?: return
        activity.setPictureInPictureParams(createPictureInPictureParams())
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createPictureInPictureParams(): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .apply {
                aspectRatio()?.let { setAspectRatio(it) }
                // Lets the system animate from where the video is instead of the whole window.
                Rect().takeIf { binding.playerView.getGlobalVisibleRect(it) }
                    ?.let { setSourceRectHint(it) }
                setActions(createPictureInPictureActions())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Unlike onUserLeaveHint(), this animates smoothly and works with gesture
                    // navigation.
                    setAutoEnterEnabled(player?.isPlaying == true)
                }
            }
            .build()

    @RequiresApi(Build.VERSION_CODES.O)
    private fun aspectRatio(): Rational? {
        val videoSize = player?.videoSize ?: return null
        if (videoSize.width <= 0 || videoSize.height <= 0) {
            return null
        }
        val rational = Rational(videoSize.width, videoSize.height)
        // Android rejects anything outside this range.
        return if (rational.toFloat() in ASPECT_RATIO_MIN..ASPECT_RATIO_MAX) rational else null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createPictureInPictureActions(): List<RemoteAction> {
        val player = player ?: return emptyList()
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
        val context = requireContext()
        val intent = Intent(ACTION_PICTURE_IN_PICTURE_CONTROL)
            .setPackage(context.packageName)
            .putExtra(EXTRA_PICTURE_IN_PICTURE_CONTROL, control)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            control,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = getString(titleRes)
        return RemoteAction(Icon.createWithResource(context, iconRes), title, title, pendingIntent)
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
        val path = currentPath ?: return
        ConfirmDeleteDialogFragment.show(path, this)
    }

    override fun delete(path: Path) {
        try {
            path.delete()
        } catch (e: IOException) {
            e.printStackTrace()
            showToast(e.toString())
            return
        }
        VideoPlaybackPositions.remove(path)
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
                maybeResumePlaybackPosition()
            }
        }
        updateTitle()
    }

    private fun share() {
        val path = currentPath ?: return
        val mimeType = MimeType.guessFromPath(path.toString())
        val intent = path.fileProviderUri.createSendStreamIntent(mimeType)
            .apply { extraPath = path }
            .withChooser()
        startActivitySafe(intent)
    }

    private fun updateTitle() {
        val path = currentPath ?: return
        requireActivity().title = path.fileName.toString()
        val size = paths.size
        binding.toolbar.subtitle = if (size > 1) {
            getString(
                R.string.video_viewer_subtitle_format,
                currentIndex + 1,
                size
            )
        } else {
            null
        }
    }

    private val currentIndex: Int
        get() = player?.currentMediaItemIndex ?: mediaItemIndex

    private val currentPath: Path?
        get() = paths.getOrNull(currentIndex)

    @Parcelize
    class Args(val intent: Intent, val position: Int) : ParcelableArgs

    @Parcelize
    private class State(
        val paths: @WriteWith<ParcelableListParceler> List<Path>,
        val mediaItemIndex: Int,
        val positionMillis: Long,
        val screenOrientationIndex: Int,
        val resizeMode: Int
    ) : ParcelableState

    companion object {
        private const val SUBTITLE_TIMEOUT_MILLIS = 5_000L

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
