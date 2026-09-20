/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Intent
import android.net.Uri
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java8.nio.file.Paths
import me.zhanghai.android.files.NoRootAccessRule
import me.zhanghai.android.files.coil.TestJpeg
import me.zhanghai.android.files.provider.archive.createArchiveRootPath
import me.zhanghai.android.files.settings.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What each row of the file list shows for itself: an icon, a thumbnail, and a badge. */
@RunWith(AndroidJUnit4::class)
class FileListIconsTest {
    @get:Rule
    val noRootAccess = NoRootAccessRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var directory: File
    private var previousViewType: FileViewType? = null

    @Before
    fun setUp() {
        executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        executeShellCommand(
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
        )
        directory = File(context.cacheDir, "icons-${UUID.randomUUID()}").apply { mkdirs() }
        TestJpeg.write(File(directory, "Photo.jpg"), 800, 600)
        File(directory, "Notes.txt").writeText("Nothing to show")
        File(directory, "com.example.app").mkdirs()
        File(context.applicationInfo.sourceDir).copyTo(File(directory, "Installer.apk"))
        Files.createSymbolicLink(
            File(directory, "Gone.txt.link").toPath(),
            File(directory, "Gone.txt").toPath()
        )
        instrumentation.runOnMainSync { previousViewType = Settings.FILE_LIST_VIEW_TYPE.value }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            previousViewType?.let { Settings.FILE_LIST_VIEW_TYPE.putValue(it) }
        }
        directory.deleteRecursively()
    }

    private fun executeShellCommand(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun setViewType(viewType: FileViewType) {
        instrumentation.runOnMainSync { Settings.FILE_LIST_VIEW_TYPE.putValue(viewType) }
    }

    private fun launch(directoryToShow: File): ActivityScenario<FileListActivity> {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(directoryToShow), "inode/directory")
            .setClass(context, FileListActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val scenario = ActivityScenario.launch<FileListActivity>(intent)
        assertNotNull(device.wait(Until.findObject(By.text("Photo.jpg")), 20_000))
        return scenario
    }

    /** Waits for the rows to be bound and their thumbnails loaded. */
    private fun ActivityScenario<FileListActivity>.awaitRows(
        condition: (Map<String, FileListAdapter.ViewHolder>) -> Boolean
    ): Map<String, FileListAdapter.ViewHolder> {
        val deadline = System.currentTimeMillis() + 20_000
        var rows: Map<String, FileListAdapter.ViewHolder> = emptyMap()
        while (System.currentTimeMillis() < deadline) {
            onActivity { activity ->
                val fragment = activity.supportFragmentManager.fragments
                    .filterIsInstance<FileListFragment>().single()
                val recyclerView = fragment.binding.recyclerView
                rows = (0 until recyclerView.childCount)
                    .mapNotNull {
                        recyclerView.getChildViewHolder(recyclerView.getChildAt(it))
                            as? FileListAdapter.ViewHolder
                    }
                    .associateBy { it.nameText.text.toString() }
            }
            if (rows.isNotEmpty() && condition(rows)) {
                return rows
            }
            Thread.sleep(200)
        }
        throw AssertionError("The rows never reached the expected state: ${rows.keys}")
    }

    @Test
    fun aPhotoShowsItsThumbnailInsteadOfItsIcon() {
        setViewType(FileViewType.LIST)
        launch(directory).use { scenario ->
            val rows = scenario.awaitRows { it["Photo.jpg"]?.thumbnailImage?.drawable != null }

            val photo = rows.getValue("Photo.jpg")
            assertTrue(photo.thumbnailImage.isVisible)
            assertFalse(photo.iconImage.isVisible)
            // A list row has no separate icon over the thumbnail.
            assertNull(photo.thumbnailIconImage)

            val notes = rows.getValue("Notes.txt")
            assertFalse(notes.thumbnailImage.isVisible)
            assertTrue(notes.iconImage.isVisible)
            assertNull(notes.thumbnailImage.drawable)
        }
    }

    @Test
    fun aDirectoryNamedAfterAnAppIsBadgedWithItsIcon() {
        setViewType(FileViewType.LIST)
        launch(directory).use { scenario ->
            val rows = scenario.awaitRows {
                it["com.example.app"]?.appIconBadgeImage?.isVisible == true
            }

            assertTrue(rows.getValue("com.example.app").appIconBadgeImage.isVisible)
            assertFalse(rows.getValue("Photo.jpg").appIconBadgeImage.isVisible)
        }
    }

    @Test
    fun aBrokenLinkIsBadgedAsAnError() {
        setViewType(FileViewType.LIST)
        launch(directory).use { scenario ->
            val rows = scenario.awaitRows { it.containsKey("Gone.txt.link") }

            val link = rows.getValue("Gone.txt.link")
            assertTrue(link.badgeImage.isVisible)
            assertFalse(rows.getValue("Notes.txt").badgeImage.isVisible)
        }
    }

    @Test
    fun anApkInAGridShowsItsAppIconAtIconSize() {
        setViewType(FileViewType.GRID)
        launch(directory).use { scenario ->
            val rows = scenario.awaitRows {
                it["Installer.apk"]?.thumbnailIconImage?.drawable != null &&
                    it["Photo.jpg"]?.thumbnailImage?.drawable != null
            }

            val apk = rows.getValue("Installer.apk")
            // The app icon goes into the small icon over the cell, not into the whole cell.
            assertTrue(apk.thumbnailIconImage!!.isVisible)
            assertFalse(apk.thumbnailImage.isVisible)

            val photo = rows.getValue("Photo.jpg")
            assertTrue(photo.thumbnailImage.isVisible)
            assertFalse(photo.thumbnailIconImage!!.isVisible)
            // A grid cell shows the folder shape behind a directory only.
            assertEquals(false, photo.directoryThumbnailImage?.isVisible)
            assertEquals(true, rows.getValue("com.example.app").directoryThumbnailImage?.isVisible)
        }
    }

    @Test
    fun aPhotoInAZipShowsAThumbnailInAGridCell() {
        val zipFile = File(directory, "Album.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Photo.jpg"))
            File(directory, "Photo.jpg").inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        setViewType(FileViewType.GRID)
        val intent = FileListActivity.createViewIntent(
            Paths.get(zipFile.path).createArchiveRootPath()
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<FileListActivity>(intent).use { scenario ->
            assertNotNull(device.wait(Until.findObject(By.text("Photo.jpg")), 20_000))

            val rows = scenario.awaitRows { it["Photo.jpg"]?.thumbnailImage?.drawable != null }

            val photo = rows.getValue("Photo.jpg")
            assertTrue(photo.thumbnailImage.isVisible)
            assertFalse(photo.thumbnailIconImage!!.isVisible)
        }
    }
}
