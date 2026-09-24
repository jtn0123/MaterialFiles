/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.core.content.getSystemService
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.NoRootAccessRule
import me.zhanghai.android.files.R
import me.zhanghai.android.files.navigation.BookmarkDirectories
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What the menu of a single row in the file list does to the file it stands for. */
@RunWith(AndroidJUnit4::class)
class FileListRowMenuTest {
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
        directory = File(context.filesDir, "row-menu-${UUID.randomUUID()}").apply { mkdirs() }
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

    /** Opens the menu of the only row in the list and chooses the item with the given title. */
    private fun chooseInRowMenu(titleRes: Int) {
        device.waitForIdle()
        val menuButton = device.wait(
            Until.findObject(By.res(context.packageName, "menuButton")),
            TIMEOUT_MILLIS
        )
        assertNotNull("The row never showed its menu button", menuButton)
        menuButton!!.click()
        val title = context.getString(titleRes)
        val item = device.wait(Until.findObject(By.text(title)), TIMEOUT_MILLIS)
        assertNotNull("The row menu never showed $title", item)
        item!!.click()
    }

    private fun tapOk() {
        val ok = device.wait(
            Until.findObject(By.text(context.getString(android.R.string.ok))),
            TIMEOUT_MILLIS
        )
        assertNotNull("The dialog never showed its OK button", ok)
        ok!!.click()
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(200)
        }
        throw AssertionError(what)
    }

    @Test
    fun copyPathPutsTheWholePathOnTheClipboard() {
        val file = File(directory, "Notes.txt").apply { writeText("Nothing to see") }
        launch(file.name).use { scenario ->
            chooseInRowMenu(R.string.file_item_action_copy_path)

            var copied: String? = null
            await("The path never reached the clipboard: $copied") {
                scenario.onActivity { activity ->
                    copied = activity.getSystemService<ClipboardManager>()
                        ?.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.text?.toString()
                }
                copied == file.path
            }
            assertEquals(file.path, copied)
        }
    }

    @Test
    fun aFolderIsBookmarkedFromItsRow() {
        val folder = File(directory, "Trips").apply { mkdirs() }
        val path = Paths.get(folder.path)
        try {
            launch(folder.name).use {
                chooseInRowMenu(R.string.file_item_action_add_bookmark)

                await("The folder was never bookmarked") { bookmarkedPaths().contains(path) }
            }
        } finally {
            instrumentation.runOnMainSync {
                Settings.BOOKMARK_DIRECTORIES.valueCompat
                    .filter { it.path == path }
                    .forEach { BookmarkDirectories.remove(it) }
            }
        }
        assertFalse(bookmarkedPaths().contains(path))
    }

    private fun bookmarkedPaths(): List<Path> {
        var paths: List<Path> = emptyList()
        instrumentation.runOnMainSync {
            paths = Settings.BOOKMARK_DIRECTORIES.valueCompat.map { it.path }
        }
        return paths
    }

    @Test
    fun renamingFromTheRowMenuRenamesTheFileOnDisk() {
        val file = File(directory, "Notes.txt").apply { writeText("Nothing to see") }
        launch(file.name).use { scenario ->
            chooseInRowMenu(R.string.rename)

            FileListDialogTesting.typeName(scenario, "Renamed.txt")
            FileListDialogTesting.confirm(scenario)

            val renamed = File(directory, "Renamed.txt")
            await("The file was never renamed") { renamed.isFile && !file.exists() }
            assertEquals("Nothing to see", renamed.readText())
        }
    }

    @Test
    fun compressingFromTheRowMenuWritesAnArchiveBesideTheFile() {
        val file = File(directory, "Notes.txt").apply { writeText("Nothing to see") }
        launch(file.name).use { scenario ->
            chooseInRowMenu(R.string.file_item_action_archive)

            assertNotNull(
                "The create archive dialog never opened",
                device.wait(
                    Until.findObject(
                        By.text(
                            context.getString(
                                R.string.file_create_archive_title
                            )
                        )
                    ),
                    TIMEOUT_MILLIS
                )
            )
            FileListDialogTesting.awaitNameDialog(scenario)
            FileListDialogTesting.confirm(scenario)

            // The name the dialog offers is the whole file name, so the archive is Notes.txt.zip.
            await("The archive was never written") {
                directory.listFiles().orEmpty().any { it.name.endsWith(".zip") && it.length() > 0 }
            }
            assertTrue("The original file should be left alone", file.isFile)
        }
    }

    @Test
    fun deletingFromTheRowMenuAsksFirstAndThenRemovesTheFile() {
        val file = File(directory, "Notes.txt").apply { writeText("Nothing to see") }
        launch(file.name).use {
            chooseInRowMenu(R.string.delete)

            val message = context.getString(
                R.string.file_delete_message_file_format,
                file.name
            )
            assertNotNull(
                "The delete confirmation never asked about ${file.name}",
                device.wait(Until.findObject(By.text(message)), TIMEOUT_MILLIS)
            )
            assertTrue("Nothing should be deleted before it is confirmed", file.isFile)
            tapOk()

            await("The file was never deleted") { !file.exists() }
        }
    }

    companion object {
        private const val TIMEOUT_MILLIS = 20_000L
    }
}
