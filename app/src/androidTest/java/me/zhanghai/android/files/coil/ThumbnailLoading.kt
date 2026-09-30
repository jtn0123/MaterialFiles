/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.decode.ImageDecoderDecoder
import coil.request.CachePolicy
import coil.request.Disposable
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.runBlocking
import me.zhanghai.android.files.provider.archive.createArchiveRootPath
import me.zhanghai.android.files.provider.common.readAttributes

/**
 * Loads thumbnails the way the file list does, but through an image loader of our own so that a
 * second load is answered from disk rather than from memory.
 */
class ThumbnailLoading(private val directory: File) {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val imageLoader =
        ImageLoader.Builder(context)
            .components {
                add(PathAttributesKeyer())
                add(PathAttributesFetcher.Factory(context))
                add(ImageDecoderDecoder.Factory())
            }
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()

    /** A file on the device itself, which is never read into the thumbnail cache. */
    fun localPath(name: String): Path = Paths.get(File(directory, name).path)

    /**
     * A file inside a zip, which the app treats like a file on a server: it is not local, and
     * reading it is worth avoiding.
     */
    fun remotePath(zipName: String, vararg names: String): Path {
        val zipFile = File(directory, zipName)
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                File(directory, name).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return Paths.get(zipFile.path).createArchiveRootPath().resolve(names.first())
    }

    fun newRequest(path: Path, width: Int, height: Int, isPreview: Boolean = false): ImageRequest {
        val attributes = path.readAttributes(BasicFileAttributes::class.java)
        return ImageRequest.Builder(context)
            .data(path to attributes)
            .size(width, height)
            .allowHardware(false)
            .apply {
                if (isPreview) {
                    setParameter(RemoteThumbnails.PARAMETER_PREVIEW, true)
                }
            }
            .build()
    }

    fun load(path: Path, width: Int, height: Int, isPreview: Boolean = false): ImageResult =
        runBlocking { execute(path, width, height, isPreview) }

    suspend fun execute(
        path: Path,
        width: Int,
        height: Int,
        isPreview: Boolean = false
    ): ImageResult = imageLoader.execute(newRequest(path, width, height, isPreview))

    /** Starts loading as a row that comes into view does; disposing it is scrolling it away. */
    fun enqueue(path: Path, width: Int, height: Int): Disposable =
        imageLoader.enqueue(newRequest(path, width, height))

    fun loadSuccessfully(
        path: Path,
        width: Int,
        height: Int,
        isPreview: Boolean = false
    ): SuccessResult = when (val result = load(path, width, height, isPreview)) {
        is SuccessResult -> result
        is ErrorResult -> throw AssertionError("Failed to load $path", result.throwable)
    }

    fun loadError(path: Path, width: Int, height: Int, isPreview: Boolean = false): Throwable =
        when (val result = load(path, width, height, isPreview)) {
            is ErrorResult -> result.throwable
            is SuccessResult -> throw AssertionError("Unexpectedly loaded $path")
        }
}
