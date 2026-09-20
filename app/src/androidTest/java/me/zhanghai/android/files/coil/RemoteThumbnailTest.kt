/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.decode.DataSource
import java.io.File
import java.util.UUID
import me.zhanghai.android.files.NoRootAccessRule
import me.zhanghai.android.files.settings.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The thumbnails of files that live on a server: read once, kept on disk, and not read again when
 * the file turned out not to be showable.
 */
@RunWith(AndroidJUnit4::class)
class RemoteThumbnailTest {
    @get:Rule
    val noRootAccess = NoRootAccessRule()

    private lateinit var directory: File
    private lateinit var loading: ThumbnailLoading

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "thumbnails-${UUID.randomUUID()}").apply { mkdirs() }
        loading = ThumbnailLoading(directory)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun aFileOnAServerIsReadOnceAndThenComesFromDisk() {
        TestJpeg.write(File(directory, "photo.jpg"), 800, 600)
        val path = loading.remotePath("album.zip", "photo.jpg")

        val first = loading.loadSuccessfully(path, 96, 96)
        assertEquals(DataSource.NETWORK, first.dataSource)

        val second = loading.loadSuccessfully(path, 96, 96)
        assertEquals(DataSource.DISK, second.dataSource)
    }

    @Test
    fun aListIconAndAGridCellShareWhatIsOnDisk() {
        TestJpeg.write(File(directory, "shared.jpg"), 800, 600)
        val path = loading.remotePath("shared.zip", "shared.jpg")

        loading.loadSuccessfully(path, 64, 64)
        // Both round up to the same 256 pixel step, so the grid cell does not read the file again.
        assertEquals(DataSource.DISK, loading.loadSuccessfully(path, 200, 200).dataSource)
        // A larger cell needs a sharper thumbnail, which is not on disk yet.
        assertEquals(DataSource.NETWORK, loading.loadSuccessfully(path, 400, 400).dataSource)
    }

    @Test
    fun anImageForAViewerIsNotKeptOnDisk() {
        TestJpeg.write(File(directory, "viewer.jpg"), 1600, 1200)
        val path = loading.remotePath("viewer.zip", "viewer.jpg")

        loading.loadSuccessfully(path, 2048, 2048)

        // Nothing was kept, so the file list still has to read the file for its own thumbnail.
        assertEquals(DataSource.NETWORK, loading.loadSuccessfully(path, 96, 96).dataSource)
    }

    @Test
    fun aFileThatCannotBeShownIsNotReadAgain() {
        File(directory, "report.pdf").writeText("Not a PDF this app can render on a server")
        val path = loading.remotePath("documents.zip", "report.pdf")

        loading.loadError(path, 96, 96)

        val throwable = loading.loadError(path, 96, 96)
        assertTrue(
            throwable.toString(),
            throwable.message.orEmpty().contains("could not be shown recently")
        )
    }

    @Test
    fun readingFilesOnAServerCanBeTurnedOff() {
        TestJpeg.write(File(directory, "off.jpg"), 800, 600)
        val path = loading.remotePath("off.zip", "off.jpg")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            Settings.READ_REMOTE_FILES_FOR_THUMBNAIL.putValue(false)
        }
        try {
            val throwable = loading.loadError(path, 96, 96)
            assertTrue(
                throwable.toString(),
                throwable.message.orEmpty().contains("Cannot read")
            )
        } finally {
            instrumentation.runOnMainSync {
                Settings.READ_REMOTE_FILES_FOR_THUMBNAIL.putValue(true)
            }
        }
    }

    @Test
    fun aGridCellShowsTheThumbnailTheCameraEmbeddedWhileThePhotoIsRead() {
        TestJpeg.write(File(directory, "camera.jpg"), 1600, 1200, 160, 120)
        val path = loading.remotePath("camera.zip", "camera.jpg")

        // The cell shows the embedded thumbnail first, while the photo is still being read.
        val preview = loading.loadSuccessfully(path, 400, 400, isPreview = true)
        assertEquals(160, preview.drawable.intrinsicWidth)
        assertEquals(120, preview.drawable.intrinsicHeight)

        val result = loading.loadSuccessfully(path, 400, 400)
        // The embedded thumbnail is too small for the cell, so the photo itself was read.
        assertTrue(
            "${result.drawable.intrinsicWidth}x${result.drawable.intrinsicHeight}",
            result.drawable.intrinsicWidth > 160
        )
    }

    @Test
    fun aPhotoTakenSidewaysIsPreviewedUpright() {
        TestJpeg.write(
            File(directory, "sideways.jpg"),
            1600,
            1200,
            160,
            120,
            TestJpeg.ORIENTATION_ROTATE_90
        )
        val path = loading.remotePath("sideways.zip", "sideways.jpg")

        val result = loading.loadSuccessfully(path, 400, 400, isPreview = true)
        assertEquals(120, result.drawable.intrinsicWidth)
        assertEquals(160, result.drawable.intrinsicHeight)
    }

    @Test
    fun thereIsNothingToPreviewWithoutAnEmbeddedThumbnail() {
        TestJpeg.write(File(directory, "plain.jpg"), 1600, 1200)
        val path = loading.remotePath("plain.zip", "plain.jpg")

        val throwable = loading.loadError(path, 400, 400, isPreview = true)
        assertTrue(
            throwable.toString(),
            throwable.message.orEmpty().contains("No embedded thumbnail")
        )
    }

    @Test
    fun thereIsNothingToPreviewOnceTheThumbnailIsOnDisk() {
        TestJpeg.write(File(directory, "cached.jpg"), 1600, 1200, 160, 120)
        val path = loading.remotePath("cached.zip", "cached.jpg")

        loading.loadSuccessfully(path, 400, 400)

        val throwable = loading.loadError(path, 400, 400, isPreview = true)
        assertTrue(throwable.toString(), throwable.message.orEmpty().contains("is on disk"))
    }

    @Test
    fun thereIsNoPreviewForAFileThatIsNotAJpeg() {
        File(directory, "drawing.png").outputStream().use {
            TestJpeg.createImage(400, 300).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val path = loading.remotePath("drawing.zip", "drawing.png")

        val throwable = loading.loadError(path, 400, 400, isPreview = true)
        assertTrue(throwable.toString(), throwable.message.orEmpty().contains("No preview for"))
    }
}
