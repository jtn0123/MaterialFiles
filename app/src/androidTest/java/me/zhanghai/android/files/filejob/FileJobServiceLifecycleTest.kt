/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java8.nio.file.Paths
import me.zhanghai.android.files.UiFailureDiagnosticsRule
import me.zhanghai.android.files.filelist.FileListActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The job service is stopped when the system reclaims it, and it takes the jobs it was running
 * down with it instead of leaving them half done and holding the device awake.
 */
@RunWith(AndroidJUnit4::class)
class FileJobServiceLifecycleTest {
    @get:Rule
    val diagnostics = UiFailureDiagnosticsRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val directory =
        File(Environment.getExternalStorageDirectory(), "Movies/FileJobServiceLifecycleTest")
    private val sourceDirectory = File(directory, "source")
    private val targetDirectory = File(directory, "target")
    private var scenario: ActivityScenario<FileListActivity>? = null

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        sourceDirectory.mkdirs()
        targetDirectory.mkdirs()
        File(sourceDirectory, FILE_NAME).writeText("source")
        File(targetDirectory, FILE_NAME).writeText("target")
    }

    @After
    fun tearDown() {
        scenario?.close()
        directory.deleteRecursively()
    }

    @Test
    fun stoppingTheServiceCancelsTheJobItWasRunning() {
        startAConflictingCopy()
        assertEquals("The copy is not running", 1, runningJobCount())

        instrumentation.runOnMainSync {
            context.stopService(Intent(context, FileJobService::class.java))
        }

        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (runningJobCount() != 0) {
            if (System.currentTimeMillis() >= deadline) {
                fail("The copy outlived the service that ran it")
            }
            Thread.sleep(POLL_MILLIS)
        }
        assertFalse(
            "The stopped job service still holds its wake lock",
            shell("dumpsys power")
                .lineSequence()
                .dropWhile { !it.startsWith("Wake Locks: size=") }
                .drop(1)
                .takeWhile { it.startsWith("  ") }
                .any { it.contains("_WAKE_LOCK") && it.contains("'FileJobService'") }
        )
        // The answer nobody gave is not assumed: the file the copy stopped at is left alone.
        assertEquals("target", File(targetDirectory, FILE_NAME).readText())
        assertEquals(listOf(FILE_NAME), targetDirectory.list()!!.sorted())
    }

    /** A copy onto an existing file waits for the conflict dialog, which nobody answers here. */
    private fun startAConflictingCopy() {
        // The dialog is only shown directly while the app is in the foreground.
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(targetDirectory), "inode/directory")
            .setClass(context, FileListActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        scenario = ActivityScenario.launch(intent)
        assertNotNull(
            "The file list never appeared",
            device.wait(Until.findObject(By.text(FILE_NAME)), TIMEOUT_MILLIS)
        )
        instrumentation.runOnMainSync {
            FileJobService.copy(
                listOf(Paths.get(File(sourceDirectory, FILE_NAME).path)),
                Paths.get(targetDirectory.path),
                context
            )
        }
        assertNotNull(
            "The conflict dialog never appeared",
            device.wait(Until.findObject(By.text("Skip")), TIMEOUT_MILLIS)
        )
    }

    private fun runningJobCount(): Int {
        var count = 0
        instrumentation.runOnMainSync { count = FileJobService.runningJobCount }
        return count
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { String(it.readBytes()) }
        }

    companion object {
        private const val FILE_NAME = "conflict.txt"
        private const val TIMEOUT_MILLIS = 30_000L
        private const val POLL_MILLIS = 100L
    }
}
