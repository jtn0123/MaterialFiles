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
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import me.zhanghai.android.files.NoRootAccessRule
import me.zhanghai.android.files.R
import me.zhanghai.android.files.UiFailureDiagnosticsRule
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Going to a folder by typing where it is. */
@RunWith(AndroidJUnit4::class)
class FileListNavigateToTest {
    @get:Rule
    val noRootAccess = NoRootAccessRule()

    @get:Rule
    val diagnostics = UiFailureDiagnosticsRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var directory: File
    private lateinit var subdirectory: File
    private var scenario: ActivityScenario<FileListActivity>? = null

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        directory = File(context.cacheDir, "navigate-${UUID.randomUUID()}").apply { mkdirs() }
        subdirectory = File(directory, "Docs").apply { mkdirs() }
        File(directory, "Outside.txt").writeText("Nothing to see")
        File(subdirectory, "Inside.txt").writeText("Nothing to see either")
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
        assertNotNull(
            "The file list never appeared",
            device.wait(Until.findObject(By.text("Outside.txt")), TIMEOUT_MILLIS)
        )
    }

    /** Opens the dialog that asks where to go, and returns its text field. */
    private fun openNavigateToDialog(): UiObject2 {
        device.waitForIdle()
        val overflow = device.wait(Until.findObject(By.desc("More options")), TIMEOUT_MILLIS)
        assertNotNull("The toolbar never showed its overflow", overflow)
        overflow!!.click()
        val item = device.wait(
            Until.findObject(By.text(context.getString(R.string.file_list_action_navigate_to))),
            TIMEOUT_MILLIS
        )
        assertNotNull("The menu never showed the navigate to item", item)
        item!!.click()
        val nameEdit = device.wait(
            Until.findObject(By.res(context.packageName, "nameEdit")),
            TIMEOUT_MILLIS
        )
        assertNotNull("The navigate to dialog never opened", nameEdit)
        return nameEdit!!
    }

    /** Confirms the dialog the way a keyboard does, which the soft one cannot cover up. */
    private fun confirm() {
        device.pressEnter()
    }

    @Test
    fun typingAFolderPathGoesThere() {
        openDirectory()

        openNavigateToDialog().text = subdirectory.path
        confirm()

        assertNotNull(
            "The file list never went to the folder that was typed in",
            device.wait(Until.findObject(By.text("Inside.txt")), TIMEOUT_MILLIS)
        )
    }

    @Test
    fun aPathThatIsNotOneIsRefusedAndTheDialogStaysOpen() {
        openDirectory()

        val nameEdit = openNavigateToDialog()
        nameEdit.text = "not a path"
        confirm()

        assertNotNull(
            "The dialog never said the path was invalid",
            device.wait(
                Until.findObject(
                    By.text(context.getString(R.string.file_list_path_error_invalid))
                ),
                TIMEOUT_MILLIS
            )
        )
        // Nothing moved: the list is still where it was, behind the dialog.
        device.pressBack()
        assertNotNull(device.wait(Until.findObject(By.text("Outside.txt")), TIMEOUT_MILLIS))
    }

    companion object {
        private const val TIMEOUT_MILLIS = 20_000L
    }
}
