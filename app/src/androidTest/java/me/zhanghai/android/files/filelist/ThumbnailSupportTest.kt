/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.NoRootAccessRule
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.provider.archive.createArchiveRootPath
import me.zhanghai.android.files.settings.Settings
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Which files the list shows a thumbnail for, before anything is read. */
@RunWith(AndroidJUnit4::class)
class ThumbnailSupportTest {
    @get:Rule
    val noRootAccess = NoRootAccessRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var directory: File
    private var previousReadRemote: Boolean? = null

    @Before
    fun setUp() {
        directory = File(context.cacheDir, "thumbnail-support-${UUID.randomUUID()}")
            .apply { mkdirs() }
        instrumentation.runOnMainSync {
            previousReadRemote = Settings.READ_REMOTE_FILES_FOR_THUMBNAIL.value
            Settings.READ_REMOTE_FILES_FOR_THUMBNAIL.putValue(true)
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            previousReadRemote?.let { Settings.READ_REMOTE_FILES_FOR_THUMBNAIL.putValue(it) }
        }
        directory.deleteRecursively()
    }

    /** A real file on the device, described as being of the given type. */
    private fun fileItem(name: String, mimeType: String): FileItem =
        Paths.get(File(directory, name).apply { writeText("Contents") }.path)
            .loadFileItem()
            .copy(mimeType = mimeType.asMimeType())

    /** The same file, but inside an archive, which is read over a connection-less provider. */
    private fun archivedPath(name: String): Path {
        val zipFile = File(directory, "Archive.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write("Contents".toByteArray())
            zip.closeEntry()
        }
        return Paths.get(zipFile.path).createArchiveRootPath().resolve(name)
    }

    @Test
    fun theImageTypesAndroidCanDecodeAreShown() {
        for (mimeType in listOf("image/jpeg", "image/png", "image/webp", "image/x-sony-arw")) {
            assertTrue(mimeType, fileItem("Photo", mimeType).supportsThumbnail)
        }
    }

    @Test
    fun anImageTypeAndroidCannotDecodeIsNotWorthReading() {
        // A server would send the whole file only for the decoder to fail on it.
        for (mimeType in listOf("image/tiff", "image/vnd.adobe.photoshop", "image/x-xcf")) {
            assertFalse(mimeType, fileItem("Drawing", mimeType).supportsThumbnail)
        }
    }

    @Test
    fun mediaAndPackagesAndDocumentsAreShown() {
        assertTrue(fileItem("Clip.mp4", "video/mp4").supportsThumbnail)
        assertTrue(fileItem("Song.mp3", "audio/mpeg").supportsThumbnail)
        assertTrue(
            fileItem("Installer.apk", "application/vnd.android.package-archive")
                .supportsThumbnail
        )
        assertTrue(fileItem("Document.pdf", "application/pdf").supportsThumbnail)
    }

    @Test
    fun anythingElseKeepsItsIcon() {
        assertFalse(fileItem("Notes.txt", "text/plain").supportsThumbnail)
        assertFalse(fileItem("Archive.tar", "application/x-tar").supportsThumbnail)
    }

    @Test
    fun aFileOffTheDeviceIsOnlyReadWhenTheUserAllowsIt() {
        val item = fileItem("Photo.jpg", "image/jpeg").copy(path = archivedPath("Photo.jpg"))

        assertTrue(item.supportsThumbnail)

        instrumentation.runOnMainSync {
            Settings.READ_REMOTE_FILES_FOR_THUMBNAIL.putValue(false)
        }

        assertFalse(item.supportsThumbnail)
        // A file on the device is still shown.
        assertTrue(fileItem("Local.jpg", "image/jpeg").supportsThumbnail)
    }
}
