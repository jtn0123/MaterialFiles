/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.content.Intent
import android.os.Environment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java8.nio.file.Paths
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deleting from the player and then recreating the activity (rotation, or coming back after the
 * process was killed) must rebuild the playlist without the deleted video: the saved state only
 * holds what was deleted, not the whole list, since the whole list can be too large to save.
 */
@RunWith(AndroidJUnit4::class)
class VideoViewerDeleteStateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val directory =
        File(Environment.getExternalStorageDirectory(), "Movies/MaterialFilesTest")
    private var scenario: ActivityScenario<VideoViewerActivity>? = null

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        directory.mkdirs()
        for (name in CLIP_NAMES) {
            instrumentation.context.assets.open("clip.mp4").use { input ->
                File(directory, name).outputStream().use { input.copyTo(it) }
            }
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        directory.deleteRecursively()
    }

    @Test
    fun deletedVideoStaysGoneAfterRecreation() {
        val paths = CLIP_NAMES.map { Paths.get(File(directory, it).path) }
        val intent = Intent(context, VideoViewerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .also { VideoViewerActivity.putExtras(it, paths, 0) }
        val scenario = ActivityScenario.launch<VideoViewerActivity>(intent).also { scenario = it }

        assertNotNull(
            "The player never appeared",
            device.wait(Until.findObject(By.res(context.packageName, "playerView")), TIMEOUT_MILLIS)
        )
        assertNotNull("Expected 1/3 in the title", waitForText("1/3"))

        // The app bar and its overflow menu show along with the playback controls.
        device.findObject(By.res(context.packageName, "playerView")).click()
        val overflow = device.wait(Until.findObject(By.desc("More options")), TIMEOUT_MILLIS)
        assertNotNull("The overflow menu button never appeared", overflow)
        overflow.click()
        device.wait(Until.findObject(By.text("Delete")), TIMEOUT_MILLIS).click()
        device.wait(Until.findObject(By.text("OK")), TIMEOUT_MILLIS).click()
        assertNotNull("Expected 1/2 after deleting the first video", waitForText("1/2"))
        assertFalse(File(directory, CLIP_NAMES[0]).exists())

        scenario.recreate()

        assertNotNull(
            "The player never reappeared",
            device.wait(Until.findObject(By.res(context.packageName, "playerView")), TIMEOUT_MILLIS)
        )
        assertNotNull("Expected 1/2 to survive recreation", waitForText("1/2"))
    }

    /**
     * The title lives in the app bar, which hides together with the playback controls a few
     * seconds after the last tap, so tap the player to bring it back when it is not showing.
     */
    private fun waitForText(text: String): UiObject2? {
        device.wait(Until.findObject(By.text(text)), SHORT_TIMEOUT_MILLIS)?.let { return it }
        device.findObject(By.res(context.packageName, "playerView"))?.click()
        return device.wait(Until.findObject(By.text(text)), TIMEOUT_MILLIS)
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
    }

    companion object {
        private val CLIP_NAMES = listOf("clip1.mp4", "clip2.mp4", "clip3.mp4")
        private const val TIMEOUT_MILLIS = 15_000L
        private const val SHORT_TIMEOUT_MILLIS = 2_000L
    }
}
