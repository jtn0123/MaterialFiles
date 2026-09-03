/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The file list screen is spread over [FileListFragment] and its delegates; this drives the
 * selection → clipboard → paste-bar → job path across all of them, which the unit tests cannot.
 *
 * Emulator only: `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class FileListCopyPasteTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val directory =
        File(Environment.getExternalStorageDirectory(), "Movies/MaterialFilesTest")
    private val subdirectory = File(directory, SUBDIRECTORY_NAME)
    private val file = File(directory, FILE_NAME)
    private var scenario: ActivityScenario<FileListActivity>? = null

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        subdirectory.mkdirs()
        file.writeText(FILE_CONTENT)
    }

    @After
    fun tearDown() {
        scenario?.close()
        directory.deleteRecursively()
    }

    @Test
    fun copyingAFileIntoASubdirectory() {
        openDirectory()
        selectFile().clickActionModeItem("Copy")
        pasteIntoSubdirectory("Copying 1")

        assertEquals(FILE_CONTENT, File(subdirectory, FILE_NAME).readText())
        assertTrue("The source is gone after a copy", file.isFile)
    }

    @Test
    fun cuttingAFileMovesItIntoASubdirectory() {
        openDirectory()
        selectFile().clickActionModeItem("Cut")
        pasteIntoSubdirectory("Moving 1")

        assertEquals(FILE_CONTENT, File(subdirectory, FILE_NAME).readText())
        assertFalse("The source is still there after a move", file.exists())
    }

    @Test
    fun selectionCountAndClearing() {
        openDirectory()
        selectFile()
        assertNotNull(
            "The action mode never showed the selection count",
            device.wait(Until.findObject(By.text("1")), TIMEOUT_MILLIS)
        )
        device.pressBack()
        assertTrue(
            "Back did not clear the selection",
            device.wait(Until.gone(By.desc("Copy")), TIMEOUT_MILLIS)
        )
        // The list itself must still be there: back only left the action mode.
        assertNotNull(device.wait(Until.findObject(By.text(FILE_NAME)), TIMEOUT_MILLIS))
    }

    private fun openDirectory() {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(directory), "inode/directory")
            .setClass(context, FileListActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        scenario = ActivityScenario.launch(intent)
        assertNotNull(
            "The file list never appeared",
            device.wait(Until.findObject(By.text(FILE_NAME)), TIMEOUT_MILLIS)
        )
    }

    private fun selectFile(): UiObject2 {
        val item = checkNotNull(device.findObject(By.text(FILE_NAME)))
        item.longClick()
        val copy = device.wait(Until.findObject(By.desc("Copy")), TIMEOUT_MILLIS)
        assertNotNull("Long-pressing the file did not start selection mode", copy)
        return copy
    }

    private fun UiObject2.clickActionModeItem(title: String) {
        val item = if (contentDescription == title) {
            this
        } else {
            checkNotNull(device.findObject(By.desc(title)))
        }
        item.click()
        assertTrue(
            "Selection mode did not end after $title",
            device.wait(Until.gone(By.desc(title)), TIMEOUT_MILLIS)
        )
    }

    private fun pasteIntoSubdirectory(pasteBarTitle: String) {
        checkNotNull(device.findObject(By.text(SUBDIRECTORY_NAME))).click()
        assertNotNull(
            "The paste bar never appeared in the subdirectory",
            device.wait(Until.findObject(By.text(pasteBarTitle)), TIMEOUT_MILLIS)
        )
        checkNotNull(device.findObject(By.desc("Paste"))).click()
        assertNotNull(
            "The pasted file never appeared in the subdirectory listing",
            device.wait(Until.findObject(By.text(FILE_NAME)), TIMEOUT_MILLIS)
        )
        assertTrue(
            "The paste bar stayed after pasting",
            device.wait(Until.gone(By.text(pasteBarTitle)), TIMEOUT_MILLIS)
        )
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
    }

    companion object {
        private const val FILE_NAME = "copy-me.txt"
        private const val FILE_CONTENT = "MaterialFilesTest"
        private const val SUBDIRECTORY_NAME = "target"
        private const val TIMEOUT_MILLIS = 15_000L
    }
}
