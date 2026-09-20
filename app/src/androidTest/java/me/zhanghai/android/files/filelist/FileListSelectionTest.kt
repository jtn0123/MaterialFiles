/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import me.zhanghai.android.files.NoRootAccessRule
import me.zhanghai.android.files.UiFailureDiagnosticsRule
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Selecting more than one row at a time, and what the selection is then good for. */
@RunWith(AndroidJUnit4::class)
class FileListSelectionTest {
    @get:Rule
    val noRootAccess = NoRootAccessRule()

    @get:Rule
    val diagnostics = UiFailureDiagnosticsRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var directory: File
    private lateinit var files: List<File>
    private var scenario: ActivityScenario<FileListActivity>? = null

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        directory = File(context.cacheDir, "selection-${UUID.randomUUID()}").apply { mkdirs() }
        files = listOf("A note.txt", "B note.txt", "C note.txt").map { name ->
            File(directory, name).apply { writeText("Nothing to see") }
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        directory.deleteRecursively()
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun openDirectory() {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(directory), "inode/directory")
            .setClass(context, FileListActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        scenario = ActivityScenario.launch(intent)
        for (file in files) {
            assertNotNull(
                "The file list never showed ${file.name}",
                device.wait(Until.findObject(By.text(file.name)), TIMEOUT_MILLIS)
            )
        }
    }

    private fun row(name: String) = checkNotNull(device.findObject(By.text(name)))

    private fun assertSelectionCount(count: Int) {
        assertNotNull(
            "The action mode never showed $count selected",
            device.wait(Until.findObject(By.text(count.toString())), TIMEOUT_MILLIS)
        )
    }

    private fun clickInActionModeOverflow(title: String) {
        device.waitForIdle()
        // Each row has a menu button described the same way; the toolbar's has no resource id.
        val overflow = device.findObjects(By.desc("More options"))
            .lastOrNull { it.resourceName?.endsWith("menuButton") != true }
        assertNotNull("The action mode never showed its overflow", overflow)
        overflow!!.click()
        val item = device.wait(Until.findObject(By.text(title)), TIMEOUT_MILLIS)
        assertNotNull("The action mode menu never showed $title", item)
        item!!.click()
    }

    @Test
    fun selectingARangeTakesInTheRowsInBetween() {
        openDirectory()

        row("A note.txt").longClick()
        assertSelectionCount(1)
        // A tap adds to the selection once there is one, instead of opening the file.
        row("C note.txt").click()
        assertSelectionCount(2)

        clickInActionModeOverflow("Select range")

        assertSelectionCount(3)

        // The middle file that was never touched is deleted along with the other two.
        checkNotNull(device.findObject(By.desc("Delete"))).click()
        val ok = device.wait(
            Until.findObject(By.text(context.getString(android.R.string.ok))),
            TIMEOUT_MILLIS
        )
        assertNotNull("The delete confirmation never appeared", ok)
        ok!!.click()

        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (files.any { it.exists() } && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
        }
        assertFalse("The selected files were not all deleted", files.any { it.exists() })
        assertTrue(directory.isDirectory)
    }

    companion object {
        private const val TIMEOUT_MILLIS = 20_000L
    }
}
