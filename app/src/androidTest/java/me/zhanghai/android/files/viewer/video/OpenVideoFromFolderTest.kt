/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import me.zhanghai.android.files.UiFailureDiagnosticsRule
import me.zhanghai.android.files.filelist.FileListActivity
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for the path that was only ever verified by hand: open a folder, tap a video, and
 * end up in the built-in player.
 *
 * Run it on the emulator only (the tablet is a kiosk):
 * `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class OpenVideoFromFolderTest {
    @get:Rule
    val diagnostics = UiFailureDiagnosticsRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val packageName = instrumentation.targetContext.packageName
    private val directory =
        File(Environment.getExternalStorageDirectory(), "Movies/MaterialFilesTest")
    private var scenario: ActivityScenario<FileListActivity>? = null

    @Before
    fun setUp() {
        // Storage access and notifications would otherwise each show a dialog over the list.
        shell("appops set $packageName MANAGE_EXTERNAL_STORAGE allow")
        shell("pm grant $packageName android.permission.POST_NOTIFICATIONS")
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
    fun tappingAVideoOpensTheBuiltInPlayer() {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(directory), "inode/directory")
            .setClass(instrumentation.targetContext, FileListActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        scenario = ActivityScenario.launch(intent)

        val clip = device.wait(Until.findObject(By.text(CLIP_NAME)), TIMEOUT_MILLIS)
        assertNotNull("The clip never appeared in the file list", clip)
        clip.click()

        val player = device.wait(
            Until.findObject(By.res(packageName, "playerView")),
            TIMEOUT_MILLIS
        )
        assertNotNull("The player view never appeared", player)
        assertTrue(
            "VideoViewerActivity is not the resumed activity",
            device.wait(Until.hasObject(By.res(packageName, "playerView")), TIMEOUT_MILLIS) &&
                resumedActivities().any { it is VideoViewerActivity }
        )
    }

    private fun resumedActivities(): List<Any> {
        var activities: Collection<Any> = emptyList()
        instrumentation.runOnMainSync {
            activities = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
        }
        return activities.toList()
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            // The command only completes once its output is drained.
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
    }

    companion object {
        private const val CLIP_NAME = "clip.mp4"
        private const val TIMEOUT_MILLIS = 30_000L
    }
}
