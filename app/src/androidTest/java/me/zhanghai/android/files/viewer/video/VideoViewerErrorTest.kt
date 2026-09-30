/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java8.nio.file.Paths
import kotlin.random.Random
import me.zhanghai.android.files.NoRootAccessRule
import me.zhanghai.android.files.R
import me.zhanghai.android.files.UiFailureDiagnosticsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A video that cannot be played says so over the player, with the name of the video and why, and
 * offers to try again.
 */
@RunWith(AndroidJUnit4::class)
class VideoViewerErrorTest {
    @get:Rule
    val noRootAccess = NoRootAccessRule()

    @get:Rule
    val diagnostics = UiFailureDiagnosticsRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File(context.filesDir, "video-error-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun aDamagedVideoSaysWhyItCannotBePlayedAndCanBeRetried() {
        // Not a video at all, only named like one.
        val file = File(directory, "Broken.mp4").apply {
            writeBytes(Random(42).nextBytes(64 * 1024))
        }
        val intent = Intent(context, VideoViewerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .also { VideoViewerActivity.putExtras(it, listOf(Paths.get(file.path)), 0) }
        ActivityScenario.launch<VideoViewerActivity>(intent).use { scenario ->
            waitForError(scenario)
            scenario.onActivity { activity ->
                val lines = activity.findViewById<TextView>(R.id.errorText).text.lines()
                assertEquals(
                    context.getString(R.string.video_viewer_error_format, "Broken.mp4"),
                    lines[0]
                )
                // The name alone says nothing about why, so the reason follows it, and not the
                // dump of the format that the extractor gave up on.
                val reasons = listOf(
                    context.getString(R.string.video_viewer_error_unsupported),
                    context.getString(R.string.video_viewer_error_malformed)
                )
                assertTrue(lines.toString(), lines.getOrNull(1) in reasons)

                activity.findViewById<View>(R.id.retryButton).performClick()
                // Trying again hides the error while the player prepares the video again.
                assertFalse(activity.findViewById<View>(R.id.errorLayout).isVisible)
            }
            // The video is still broken, so the error comes back.
            waitForError(scenario)
            scenario.onActivity { activity ->
                val text = activity.findViewById<TextView>(R.id.errorText).text.toString()
                assertTrue(
                    text,
                    text.startsWith(
                        context.getString(R.string.video_viewer_error_format, "Broken.mp4")
                    )
                )
            }
        }
    }

    private fun waitForError(scenario: ActivityScenario<VideoViewerActivity>) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            var isShown = false
            scenario.onActivity { isShown = it.findViewById<View>(R.id.errorLayout).isVisible }
            if (isShown) {
                return
            }
            Thread.sleep(100)
        }
        throw AssertionError("The playback error never showed")
    }

    companion object {
        private const val TIMEOUT_MILLIS = 30_000L
    }
}
