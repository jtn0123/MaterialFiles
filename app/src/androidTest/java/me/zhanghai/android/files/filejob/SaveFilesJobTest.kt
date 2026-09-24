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
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.UiFailureDiagnosticsRule
import me.zhanghai.android.files.filelist.FileListActivity
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
 * Saving files shared from another app: the sender gives the content and a display name for each
 * of them, and the job writes them into the directory the user picked.
 */
@RunWith(AndroidJUnit4::class)
class SaveFilesJobTest {
    @get:Rule
    val diagnostics = UiFailureDiagnosticsRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val directory =
        File(Environment.getExternalStorageDirectory(), "Download/SaveFilesJobTest")
    private val sourceDirectory = File(directory, "source")
    private val targetDirectory = File(directory, "target")
    private var scenario: ActivityScenario<FileListActivity>? = null

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        sourceDirectory.mkdirs()
        targetDirectory.mkdirs()
    }

    @After
    fun tearDown() {
        scenario?.close()
        directory.deleteRecursively()
    }

    @Test
    fun eachSourceIsSavedUnderTheNameItWasGiven() {
        File(sourceDirectory, "0.tmp").writeText("first")
        File(sourceDirectory, "1.tmp").writeText("second")
        showTheTargetDirectory()

        saveAll(
            listOf(path(sourceDirectory, "0.tmp"), path(sourceDirectory, "1.tmp")),
            listOf("first.txt", "second.txt")
        )

        assertEquals("first", awaitFile("first.txt").readText())
        assertEquals("second", awaitFile("second.txt").readText())
        awaitNoRunningJob()
    }

    /** Nothing is saved without a name for it, and that is a programming error, not a failure. */
    @Test
    fun everySourceNeedsAName() {
        val source = path(sourceDirectory, "0.tmp")

        try {
            SaveFilesJob(listOf(source, source), Paths.get(targetDirectory.path), listOf("one"))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Every source needs a name", e.message)
        }
    }

    private fun showTheTargetDirectory() {
        // A job is only started while the app is in the foreground, and its dialogs need an
        // activity to show up over.
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(targetDirectory), "inode/directory")
            .setClass(context, FileListActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        scenario = ActivityScenario.launch(intent)
        assertNotNull(
            "The file list never appeared",
            device.wait(Until.findObject(By.text("target")), TIMEOUT_MILLIS)
        )
    }

    private fun path(directory: File, name: String): Path = Paths.get(File(directory, name).path)

    private fun saveAll(sources: List<Path>, names: List<String>) {
        instrumentation.runOnMainSync {
            FileJobService.saveAll(sources, Paths.get(targetDirectory.path), names, context)
        }
    }

    private fun awaitFile(name: String): File {
        val file = File(targetDirectory, name)
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!file.exists()) {
            if (System.currentTimeMillis() >= deadline) {
                fail("$name was never saved, the target holds ${targetDirectory.list()?.toList()}")
            }
            Thread.sleep(POLL_MILLIS)
        }
        return file
    }

    private fun awaitNoRunningJob() {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (runningJobCount() != 0) {
            if (System.currentTimeMillis() >= deadline) {
                fail("The job never finished")
            }
            Thread.sleep(POLL_MILLIS)
        }
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
        private const val TIMEOUT_MILLIS = 30_000L
        private const val POLL_MILLIS = 100L
    }
}
