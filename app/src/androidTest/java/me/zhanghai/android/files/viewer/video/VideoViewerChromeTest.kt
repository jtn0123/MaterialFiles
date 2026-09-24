/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Environment
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.UiFailureDiagnosticsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The video viewer's app bar follows the playback controls, the fullscreen button switches between
 * fitting and filling the screen, and the screen orientation menu item cycles the orientation.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class VideoViewerChromeTest {
    @get:Rule
    val diagnostics = UiFailureDiagnosticsRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val directory =
        File(Environment.getExternalStorageDirectory(), "Movies/VideoViewerChromeTest")
    private var scenario: ActivityScenario<VideoViewerActivity>? = null

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        directory.mkdirs()
        instrumentation.context.assets.open("clip.mp4").use { input ->
            File(directory, CLIP_NAME).outputStream().use { input.copyTo(it) }
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        directory.deleteRecursively()
    }

    @Test
    fun theAppBarHidesAndShowsWithThePlaybackControls() {
        launch()

        onPlayerView { it.showController() }
        awaitAppBarAlpha(1f)
        onPlayerView { it.hideController() }
        awaitAppBarAlpha(0f)
        onPlayerView { it.showController() }
        awaitAppBarAlpha(1f)
    }

    @Test
    fun theFullscreenButtonTogglesBetweenFittingAndFillingTheScreen() {
        launch()
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, resizeModeAfter {})

        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, resizeModeAfter(::clickFullscreen))
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, resizeModeAfter(::clickFullscreen))
    }

    @Test
    fun theScreenOrientationItemCyclesThroughLandscapePortraitAndAuto() {
        launch()

        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            requestedOrientationAfterScreenOrientationItem()
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            requestedOrientationAfterScreenOrientationItem()
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            requestedOrientationAfterScreenOrientationItem()
        )
    }

    private fun launch() {
        val intent = Intent(context, VideoViewerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .also {
                VideoViewerActivity.putExtras(
                    it,
                    listOf(Paths.get(File(directory, CLIP_NAME).path)),
                    0
                )
            }
        scenario = ActivityScenario.launch(intent)
        assertNotNull(
            "The player never appeared",
            device.wait(Until.findObject(By.res(context.packageName, "playerView")), TIMEOUT_MILLIS)
        )
    }

    private fun onPlayerView(action: (PlayerView) -> Unit) {
        checkNotNull(scenario).onActivity { action(it.findViewById(R.id.playerView)) }
    }

    private fun resizeModeAfter(action: (PlayerView) -> Unit): Int {
        var resizeMode = -1
        onPlayerView {
            action(it)
            resizeMode = it.resizeMode
        }
        return resizeMode
    }

    private fun clickFullscreen(playerView: PlayerView) {
        val button = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_fullscreen)
        assertNotNull("The player has no fullscreen button", button)
        assertTrue("The fullscreen button did nothing", button.performClick())
    }

    private fun requestedOrientationAfterScreenOrientationItem(): Int {
        var requestedOrientation = -1
        checkNotNull(scenario).onActivity { activity ->
            val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu
            assertNotNull(
                "The app bar has no screen orientation item",
                menu.findItem(R.id.action_screen_orientation)
            )
            assertTrue(menu.performIdentifierAction(R.id.action_screen_orientation, 0))
            requestedOrientation = activity.requestedOrientation
        }
        return requestedOrientation
    }

    /** The app bar fades along with the playback controls, so wait for the fade to finish. */
    private fun awaitAppBarAlpha(expected: Float) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        var alpha = Float.NaN
        while (System.currentTimeMillis() < deadline) {
            checkNotNull(scenario).onActivity {
                alpha = it.findViewById<View>(R.id.appBarLayout).alpha
            }
            if (alpha == expected) {
                return
            }
            Thread.sleep(POLL_MILLIS)
        }
        fail("The app bar stayed at alpha $alpha instead of $expected")
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
    }

    companion object {
        private const val CLIP_NAME = "clip.mp4"
        private const val TIMEOUT_MILLIS = 30_000L
        private const val POLL_MILLIS = 100L
    }
}
