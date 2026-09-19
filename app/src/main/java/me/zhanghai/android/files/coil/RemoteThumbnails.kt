/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import androidx.core.graphics.drawable.toBitmap
import androidx.exifinterface.media.ExifInterface
import coil.annotation.ExperimentalCoilApi
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.disk.DiskCache
import coil.fetch.SourceResult
import java.io.Closeable
import java.io.IOException
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.file.lastModifiedInstant
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.util.closeSafe
import me.zhanghai.android.files.util.logWarning
import okio.buffer

/**
 * What keeps thumbnails of files on a server affordable: they are kept on disk so that a folder
 * is only ever read once, only a few are read at a time so that they do not starve each other
 * (and whatever else uses the connection) of bandwidth, and a read for a row that has scrolled
 * away is given up.
 */
@OptIn(ExperimentalCoilApi::class)
internal object RemoteThumbnails {
    /** Thumbnails larger than this are for a viewer rather than a list, and are not kept. */
    const val MAX_SIZE_PX = 1024

    /**
     * Set on a request that only wants the thumbnail a camera embedded in a JPEG, to show while
     * the sharp one is read.
     */
    const val PARAMETER_PREVIEW = "remote_thumbnail_preview"

    private const val SIZE_STEP_PX = 256

    private const val MAX_PARALLELISM = 4

    /** Previews are a few dozen kilobytes each, so they are not queued behind whole files. */
    private const val MAX_PREVIEW_PARALLELISM = 4

    private const val MAX_UNREADABLE_COUNT = 1024

    /** Long enough not to read a file that cannot be shown again while scrolling back and forth. */
    private const val UNREADABLE_EXPIRY_MILLIS = 10L * 60 * 1000

    private const val MAX_CACHE_SIZE_BYTES = 128L * 1024 * 1024

    private const val CACHE_MIME_TYPE = "image/webp"

    private const val CACHE_QUALITY = 85

    private val semaphore = Semaphore(MAX_PARALLELISM)

    private val previewSemaphore = Semaphore(MAX_PREVIEW_PARALLELISM)

    /** Files that were read and could not be shown. */
    private val unreadable =
        RecentFailures(MAX_UNREADABLE_COUNT, UNREADABLE_EXPIRY_MILLIS, SystemClock::elapsedRealtime)

    private val diskCache: DiskCache by lazy {
        DiskCache.Builder()
            .directory(application.cacheDir.resolve("remote_thumbnails"))
            .maxSizeBytes(MAX_CACHE_SIZE_BYTES)
            .build()
    }

    /** Requests waiting here are cancellable, so a fling does not queue reads nobody wants. */
    suspend fun <T> withReadPermit(block: suspend () -> T): T = semaphore.withPermit { block() }

    suspend fun <T> withPreviewPermit(block: suspend () -> T): T =
        previewSemaphore.withPermit { block() }

    /** Rounds a thumbnail size up to a step; a size for a viewer is left as it is. */
    fun roundSize(sizePx: Int): Int = if (sizePx > MAX_SIZE_PX) {
        sizePx
    } else {
        ((sizePx + SIZE_STEP_PX - 1) / SIZE_STEP_PX * SIZE_STEP_PX).coerceAtLeast(SIZE_STEP_PX)
    }

    private fun createFileKey(path: Path, attributes: BasicFileAttributes): String =
        "${path.toUri()}:${attributes.lastModifiedInstant.toEpochMilli()}:${attributes.size()}"

    fun markUnreadable(path: Path, attributes: BasicFileAttributes) {
        unreadable.add(createFileKey(path, attributes))
    }

    /** Fails without reading [path] again if it recently could not be shown. */
    fun checkNotUnreadable(path: Path, attributes: BasicFileAttributes) {
        check(createFileKey(path, attributes) !in unreadable) {
            "$path could not be shown recently"
        }
    }

    fun createKey(path: Path, attributes: BasicFileAttributes, width: Int, height: Int): String =
        "${createFileKey(path, attributes)}:${width}x$height"

    fun contains(key: String): Boolean = try {
        diskCache.openSnapshot(key)?.use { true } ?: false
    } catch (e: Exception) {
        e.logWarning(TAG, "Open the cached thumbnail $key")
        false
    }

    fun get(key: String): SourceResult? {
        val snapshot = try {
            diskCache.openSnapshot(key)
        } catch (e: Exception) {
            e.logWarning(TAG, "Open the cached thumbnail $key")
            null
        } ?: return null
        val source = ImageSource(snapshot.data, diskCache.fileSystem, key, snapshot)
        return SourceResult(source, CACHE_MIME_TYPE, DataSource.DISK)
    }

    fun put(key: String, drawable: Drawable) {
        // An animated drawable is kept as its first frame, which is all a list shows anyway.
        val bitmap = (drawable as? BitmapDrawable)?.bitmap
            ?: drawable.takeIf { it.intrinsicWidth > 0 && it.intrinsicHeight > 0 }?.toBitmap()
            ?: return
        try {
            val editor = diskCache.openEditor(key) ?: return
            try {
                diskCache.fileSystem.sink(editor.data).buffer().use { sink ->
                    // A hardware bitmap cannot be compressed directly.
                    val softwareBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
                        bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    } else {
                        bitmap
                    }
                    val isCompressed = softwareBitmap.compress(
                        Bitmap.CompressFormat.WEBP_LOSSY,
                        CACHE_QUALITY,
                        sink.outputStream()
                    )
                    // An empty entry would be served, and fail to decode, forever.
                    if (!isCompressed) {
                        throw IOException("Cannot compress the thumbnail for $key")
                    }
                }
                editor.commit()
            } catch (e: Exception) {
                editor.abort()
                throw e
            }
        } catch (e: Exception) {
            e.logWarning(TAG, "Cache the thumbnail $key")
        }
    }

    private const val TAG = "RemoteThumbnails"
}

/**
 * Lets a coroutine abandon blocking work that thread interruption does not reach, by closing what
 * that work reads from once the coroutine is cancelled.
 */
internal class AbortHandle : Closeable {
    private var closeable: Closeable? = null
    private var isClosed = false

    @Synchronized
    fun set(closeable: Closeable) {
        if (isClosed) {
            closeable.closeSafe()
        } else {
            this.closeable = closeable
        }
    }

    @Synchronized
    override fun close() {
        isClosed = true
        closeable?.closeSafe()
        closeable = null
    }
}

/** Runs blocking [block], closing the [AbortHandle] it is given if this coroutine is cancelled. */
internal suspend fun <T> runAbortable(block: (AbortHandle) -> T): T = coroutineScope {
    val abortHandle = AbortHandle()
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            abortHandle.close()
        }
    }
    try {
        runInterruptible { block(abortHandle) }
    } finally {
        watcher.cancel()
    }
}

/**
 * Reads the thumbnail a camera embedded in a JPEG, which sits in the first few dozen kilobytes of
 * a file that is otherwise megabytes to transfer.
 *
 * @param minSizePx the larger side of the view it is for, or 0 to take any size
 * @return the upright thumbnail, or `null` if there is none that is large enough
 */
internal fun Path.readExifThumbnail(minSizePx: Int): Bitmap? = newInputStream().use { inputStream ->
    val exifInterface = ExifInterface(inputStream)
    val thumbnail = exifInterface.thumbnailBitmap ?: return null
    // Embedded thumbnails are small; slight upscaling in a list icon is not noticeable, more is.
    val isLargeEnough = minOf(thumbnail.width, thumbnail.height) * 5 >= minSizePx * 4
    if (!isLargeEnough) {
        return null
    }
    val rotationDegrees = exifInterface.rotationDegrees
    val isFlipped = exifInterface.isFlipped
    if (rotationDegrees == 0 && !isFlipped) {
        return thumbnail
    }
    val matrix = Matrix().apply {
        if (isFlipped) {
            postScale(-1f, 1f)
        }
        postRotate(rotationDegrees.toFloat())
    }
    Bitmap.createBitmap(thumbnail, 0, 0, thumbnail.width, thumbnail.height, matrix, true)
}
