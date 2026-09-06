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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A copy that runs into an existing file blocks on the conflict dialog. While it waits, the job
 * service must not keep the device awake: a dialog nobody answers can sit there for days.
 */
@RunWith(AndroidJUnit4::class)
class FileJobWaitingWakeLockTest {
    @get:Rule
    val diagnostics = UiFailureDiagnosticsRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val directory =
        File(Environment.getExternalStorageDirectory(), "Movies/MaterialFilesTest")
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
    fun wakeLockIsReleasedWhileTheConflictDialogWaits() {
        // The dialog is only started directly while the app is in the foreground; otherwise the
        // job posts a notification and waits for that instead.
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
        val skip = device.wait(Until.findObject(By.text("Skip")), TIMEOUT_MILLIS)
        assertNotNull("The conflict dialog never appeared", skip)

        assertFalse(
            "The job service still holds its wake lock while waiting for the user",
            isWakeLockHeld()
        )

        skip.click()
        assertTrue(
            "The conflict dialog did not go away",
            device.wait(Until.gone(By.text("Skip")), TIMEOUT_MILLIS)
        )
        // The job finishes (and releases again) right after the answer.
        device.wait(Until.findObject(By.text(NEVER_APPEARING_TEXT)), 1_000)
        assertFalse("The job service holds its wake lock after the job", isWakeLockHeld())
    }

    /** Held locks are listed under "Wake Locks:" in dumpsys power; the history below is not. */
    private fun isWakeLockHeld(): Boolean = shell("dumpsys power")
        .lineSequence()
        .dropWhile { !it.startsWith("Wake Locks: size=") }
        .drop(1)
        .takeWhile { it.startsWith("  ") }
        .any { it.contains("_WAKE_LOCK") && it.contains("'FileJobService'") }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { String(it.readBytes()) }
        }

    companion object {
        private const val FILE_NAME = "conflict.txt"
        private const val NEVER_APPEARING_TEXT = "MaterialFilesTest never shows this"
        private const val TIMEOUT_MILLIS = 30_000L
    }
}
