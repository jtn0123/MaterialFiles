/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.decode.DataSource
import java.io.File
import java.util.UUID
import java8.nio.file.Paths
import me.zhanghai.android.files.NoRootAccessRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The thumbnail of a file on the device itself, for each kind the file list shows one for. */
@RunWith(AndroidJUnit4::class)
class PathAttributesFetcherTest {
    @get:Rule
    val noRootAccess = NoRootAccessRule()

    private lateinit var directory: File
    private lateinit var loading: ThumbnailLoading

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.filesDir, "local-thumbnails-${UUID.randomUUID()}")
            .apply { mkdirs() }
        loading = ThumbnailLoading(directory)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun aPhotoOnTheDeviceIsDecodedWithoutBeingKeptAsAThumbnail() {
        TestJpeg.write(File(directory, "local.jpg"), 800, 600)
        val path = loading.localPath("local.jpg")

        val first = loading.loadSuccessfully(path, 96, 96)
        assertEquals(DataSource.DISK, first.dataSource)
        assertTrue(first.drawable.intrinsicWidth > 0)
        // A local file is read directly every time, never through the thumbnail cache.
        assertEquals(DataSource.DISK, loading.loadSuccessfully(path, 96, 96).dataSource)
    }

    @Test
    fun theEmbeddedThumbnailIsNotUsedForAFileOnTheDevice() {
        // Reading the whole photo costs nothing here, so the sharp thumbnail is worth it.
        TestJpeg.write(File(directory, "camera.jpg"), 1600, 1200, 160, 120)
        val path = loading.localPath("camera.jpg")

        val result = loading.loadSuccessfully(path, 400, 400)

        assertTrue(
            "${result.drawable.intrinsicWidth}x${result.drawable.intrinsicHeight}",
            result.drawable.intrinsicWidth > 160
        )
    }

    @Test
    fun aVideoIsShownAsOneOfItsFrames() {
        val file = File(directory, "clip.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("clip.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val path = Paths.get(file.path)

        val result = loading.loadSuccessfully(path, 96, 96)

        // The clip is 64x64 and the frame is scaled to the size the icon asked for.
        assertEquals(96, result.drawable.intrinsicWidth)
        assertEquals(96, result.drawable.intrinsicHeight)
    }

    @Test
    fun aVideoInsideAnArchiveHasNoThumbnail() {
        // Frames are read at arbitrary offsets, which an archive entry cannot serve.
        val file = File(directory, "clip.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("clip.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val path = loading.remotePath("videos.zip", "clip.mp4")

        loading.loadError(path, 96, 96)

        // It is remembered as unreadable, so scrolling back does not read it again.
        val throwable = loading.loadError(path, 96, 96)
        assertTrue(
            throwable.toString(),
            throwable.message.orEmpty().contains("could not be shown recently")
        )
    }

    @Test
    fun anApkIsShownAsItsAppIcon() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = Paths.get(context.applicationInfo.sourceDir)

        val result = loading.loadSuccessfully(path, 96, 96)

        assertTrue(result.drawable.intrinsicWidth > 0)
    }

    @Test
    fun aPdfIsShownAsItsFirstPage() {
        val file = File(directory, "document.pdf")
        PdfDocument().use { document ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(200, 100, 1).create())
            page.canvas.drawCircle(100f, 50f, 40f, Paint())
            document.finishPage(page)
            file.outputStream().use { document.writeTo(it) }
        }
        val path = Paths.get(file.path)

        val result = loading.loadSuccessfully(path, 96, 96)

        assertTrue(
            "${result.drawable.intrinsicWidth}x${result.drawable.intrinsicHeight}",
            result.drawable.intrinsicWidth > result.drawable.intrinsicHeight
        )
    }

    @Test
    fun aFileThatIsNotAnImageHasNoThumbnail() {
        File(directory, "notes.txt").writeText("Nothing to show here")
        val path = loading.localPath("notes.txt")

        loading.loadError(path, 96, 96)
    }

    private inline fun <R> PdfDocument.use(block: (PdfDocument) -> R): R = try {
        block(this)
    } finally {
        close()
    }
}
