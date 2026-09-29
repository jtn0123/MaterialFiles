/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.createBitmap
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.drawable.CrossfadeDrawable
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import me.zhanghai.android.files.NoRootAccessRule
import me.zhanghai.android.files.coil.RemoteThumbnails
import me.zhanghai.android.files.coil.SlowRemoteFileSystem
import me.zhanghai.android.files.coil.TestJpeg
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.provider.common.readAttributes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** How the image viewer reads a photo and what it shows when it cannot. */
@RunWith(AndroidJUnit4::class)
class ImageViewerAdapterTest {
    @get:Rule
    val noRootAccess = NoRootAccessRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var directory: File
    private var scenario: ActivityScenario<FileListActivity>? = null

    /** Counts what the adapter hands to the dispatcher it was given for reading. */
    private val reads = AtomicInteger()
    private val readDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            reads.incrementAndGet()
            Dispatchers.IO.dispatch(context, block)
        }
    }

    @Before
    fun setUp() {
        directory = File(context.filesDir, "image-viewer-${UUID.randomUUID()}")
            .apply { mkdirs() }
    }

    @After
    fun tearDown() {
        scenario?.close()
        directory.deleteRecursively()
    }

    /**
     * Binds one page of the viewer inside a real activity, which is where a photo is loaded and
     * shown.
     */
    private fun show(path: Path): ImageViewerAdapter.ViewHolder {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(directory), "inode/directory")
            .setClass(context, FileListActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val scenario = ActivityScenario.launch<FileListActivity>(intent)
        this.scenario = scenario
        lateinit var holder: ImageViewerAdapter.ViewHolder
        scenario.onActivity { activity ->
            val parent = FrameLayout(activity)
            activity.addContentView(
                parent,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            val adapter = ImageViewerAdapter(activity, readDispatcher) {}
            adapter.replace(listOf(path))
            holder = adapter.onCreateViewHolder(parent, 0)
            adapter.onBindViewHolder(holder, 0)
            parent.addView(holder.itemView)
        }
        return holder
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            var satisfied = false
            instrumentation.runOnMainSync { satisfied = condition() }
            if (satisfied) {
                return
            }
            Thread.sleep(100)
        }
        throw AssertionError(what)
    }

    @Test
    fun aPhotoIsReadOffTheShowingThreadAndThenShown() {
        val file = File(directory, "Photo.jpg")
        TestJpeg.write(file, 320, 240)
        val holder = show(Paths.get(file.path))

        // The test photo is blue on its left half and red on its right half.
        await("The photo that was read is not the one in the file") {
            val colors = holder.binding.image.drawable.photoColors()
            colors != null && colors.first.isMostly(Color.BLUE) && colors.second.isMostly(Color.RED)
        }

        assertTrue(
            "The photo should be read on the dispatcher given to the adapter",
            reads.get() > 0
        )
        assertTrue(holder.binding.image.isVisible)
        assertFalse(holder.binding.errorLayout.isVisible)
    }

    /** The colours in the middle of the left and right halves of the photo that is shown. */
    private fun Drawable?.photoColors(): Pair<Int, Int>? {
        // While it fades in, the photo is the far end of a crossfade.
        val drawable = (this as? CrossfadeDrawable)?.end ?: this
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return null
        // A photo decoded into graphics memory has no pixels that can be read back directly.
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            bitmap
        }
        try {
            return readable.getPixel(readable.width / 4, readable.height / 2) to
                readable.getPixel(readable.width * 3 / 4, readable.height / 2)
        } finally {
            if (readable !== bitmap) {
                readable.recycle()
            }
        }
    }

    /** True when a colour is recognizably the one wanted, after scaling and JPEG compression. */
    private fun Int.isMostly(color: Int): Boolean =
        Color.red(this) > 128 == Color.red(color) > 128 &&
            Color.green(this) > 128 == Color.green(color) > 128 &&
            Color.blue(this) > 128 == Color.blue(color) > 128 &&
            Color.alpha(this) > 128

    @Test
    fun aPhotoThatIsGoneShowsWhyItCannotBeShown() {
        val holder = show(Paths.get(File(directory, "Gone.jpg").path))

        await("The error was never shown") { holder.binding.errorLayout.isVisible }

        assertTrue(
            holder.binding.errorText.text.toString(),
            holder.binding.errorText.text.contains("Gone.jpg")
        )
        assertFalse(holder.binding.image.isVisible)
        assertFalse(holder.binding.largeImage.isVisible)
    }

    @Test
    fun aDamagedPhotoShowsWhyItCannotBeShown() {
        // The file is there and can be read, but is not a photo at all.
        val file = File(directory, "Damaged.jpg").apply {
            writeBytes(Random(7).nextBytes(4096))
        }
        val holder = show(Paths.get(file.path))

        await("The error was never shown") { holder.binding.errorLayout.isVisible }

        assertTrue(holder.binding.errorText.text.isNotBlank())
        assertFalse(holder.binding.image.isVisible)
        assertFalse(holder.binding.largeImage.isVisible)
    }

    @Test
    fun aRemoteCameraPhotoShowsItsEmbeddedThumbnailWhileItIsRead() {
        val file = File(directory, "Camera.jpg")
        TestJpeg.write(file, 2400, 1800, 160, 120, isNoisy = true)
        val fileSystem = SlowRemoteFileSystem(directory)
        // Each read is a slow round trip, and the whole photo takes several.
        fileSystem.reads.latencyMillis = 400
        val holder = show(fileSystem.path("Camera.jpg"))

        await("The embedded thumbnail was not shown while the photo was read") {
            holder.binding.image.isVisible && holder.binding.image.drawable.bitmapWidth() == 160
        }
        assertTrue(
            "The progress is hidden behind the placeholder",
            holder.binding.progress.isVisible
        )
        await("The photo itself never replaced the thumbnail") {
            (holder.binding.image.drawable.bitmapWidth() ?: 0) > 160
        }
        // One read for the size, the orientation and the thumbnail, and one for the photo.
        assertEquals(2, fileSystem.reads.openedChannels("Camera.jpg"))
    }

    @Test
    fun aRemotePhotoShowsTheThumbnailTheGridKeptWhileItIsRead() {
        val file = File(directory, "Plain.jpg")
        TestJpeg.write(file, 2400, 1800, isNoisy = true)
        val fileSystem = SlowRemoteFileSystem(directory)
        val path = fileSystem.path("Plain.jpg")
        val attributes = path.readAttributes(BasicFileAttributes::class.java)
        val gridThumbnail = createBitmap(200, 150).apply { eraseColor(Color.GREEN) }
        RemoteThumbnails.put(
            RemoteThumbnails.createKey(path, attributes, 512, 512),
            BitmapDrawable(context.resources, gridThumbnail)
        )
        fileSystem.reads.latencyMillis = 400
        val holder = show(path)

        await("The thumbnail the grid kept was not shown while the photo was read") {
            holder.binding.image.isVisible && holder.binding.image.drawable.bitmapWidth() == 200
        }
        await("The photo itself never replaced the thumbnail") {
            (holder.binding.image.drawable.bitmapWidth() ?: 0) > 200
        }
    }

    @Test
    fun aPhotoIsDecodedNoLargerThanTwiceTheScreen() {
        val file = File(directory, "Wide.jpg")
        TestJpeg.write(file, 4800, 2400)
        val holder = show(Paths.get(file.path))

        await("The photo was never shown") {
            (holder.binding.image.drawable.bitmapWidth() ?: 0) > 0
        }
        var bitmap: Bitmap? = null
        instrumentation.runOnMainSync { bitmap = holder.binding.image.drawable.bitmap() }
        val displayMetrics = context.resources.displayMetrics
        val (boxWidth, boxHeight) = getViewerDecodeSize(
            4800,
            2400,
            0,
            displayMetrics.widthPixels,
            displayMetrics.heightPixels
        )
        val scale = minOf(1f, boxWidth / 4800f, boxHeight / 2400f)
        val decoded = checkNotNull(bitmap)
        val message = "Decoded ${decoded.width}x${decoded.height} for a $boxWidth x $boxHeight box"
        assertTrue(message, decoded.width <= (4800 * scale).toInt() + 1)
        assertTrue(message, decoded.height <= (2400 * scale).toInt() + 1)
        // Scaled by as much as it needs, not a whole power of two more.
        assertTrue(message, decoded.width >= (4800 * scale * 0.9f).toInt())
    }

    private fun Drawable?.bitmap(): Bitmap? {
        val drawable = (this as? CrossfadeDrawable)?.end ?: this
        return (drawable as? BitmapDrawable)?.bitmap
    }

    private fun Drawable?.bitmapWidth(): Int? = bitmap()?.width
}
