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
import me.zhanghai.android.files.R
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What TalkBack is told about a row of the file list, its selection button and its menu. */
@RunWith(AndroidJUnit4::class)
class FileListAccessibilityTest {
    @get:Rule
    val noRootAccess = NoRootAccessRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var directory: File

    @Before
    fun setUp() {
        executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        executeShellCommand(
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
        )
        directory = File(context.filesDir, "accessibility-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun executeShellCommand(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun launch(vararg names: String): ActivityScenario<FileListActivity> {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(directory), "inode/directory")
            .setClass(context, FileListActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val scenario = ActivityScenario.launch<FileListActivity>(intent)
        for (name in names) {
            assertNotNull(
                "The file list never showed $name",
                device.wait(Until.findObject(By.text(name)), TIMEOUT_MILLIS)
            )
        }
        return scenario
    }

    private fun awaitDescription(description: String) {
        assertNotNull(
            "Nothing was described as \"$description\"",
            device.wait(Until.findObject(By.desc(description)), TIMEOUT_MILLIS)
        )
    }

    @Test
    fun aFolderRowIsDescribedAsAFolder() {
        File(directory, "Trips").mkdirs()
        launch("Trips").use {
            val separator = context.getString(R.string.file_item_accessibility_separator)
            awaitDescription(
                "Trips$separator${context.getString(R.string.file_type_name_directory)}"
            )
        }
    }

    @Test
    fun aFileRowStartsWithItsNameAndType() {
        File(directory, "Notes.txt").writeText("Nothing to see")
        launch("Notes.txt").use {
            val separator = context.getString(R.string.file_item_accessibility_separator)
            val type = context.getString(R.string.file_type_name_text_plain)
            assertNotNull(
                "The row was not described with its name and type",
                device.wait(
                    Until.findObject(By.descStartsWith("Notes.txt$separator$type$separator")),
                    TIMEOUT_MILLIS
                )
            )
        }
    }

    @Test
    fun theMenuAndSelectionButtonsNameTheirFile() {
        File(directory, "Notes.txt").writeText("Nothing to see")
        launch("Notes.txt").use {
            awaitDescription(context.getString(R.string.file_item_menu_format, "Notes.txt"))
            val select = context.getString(R.string.file_item_select_format, "Notes.txt")
            awaitDescription(select)

            device.findObject(By.desc(select)).click()

            awaitDescription(context.getString(R.string.file_item_deselect_format, "Notes.txt"))
        }
    }

    companion object {
        private const val TIMEOUT_MILLIS = 20_000L
    }
}
