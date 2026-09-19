/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.ImageSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import coil.size.Dimension
import coil.size.Size
import java.io.Closeable
import java.io.IOException
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.use
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.file.isApk
import me.zhanghai.android.files.file.isImage
import me.zhanghai.android.files.file.isMedia
import me.zhanghai.android.files.file.isPdf
import me.zhanghai.android.files.file.isVideo
import me.zhanghai.android.files.file.lastModifiedInstant
import me.zhanghai.android.files.filelist.isRemotePath
import me.zhanghai.android.files.provider.common.AndroidFileTypeDetector
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.document.documentSupportsThumbnail
import me.zhanghai.android.files.provider.document.getDocumentThumbnail
import me.zhanghai.android.files.provider.document.isDocumentPath
import me.zhanghai.android.files.provider.document.openDocumentParcelFileDescriptor
import me.zhanghai.android.files.provider.ftp.isFtpPath
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.closeSafe
import me.zhanghai.android.files.util.getDimensionPixelSize
import me.zhanghai.android.files.util.getPackageArchiveInfoCompat
import me.zhanghai.android.files.util.isGetPackageArchiveInfoCompatible
import me.zhanghai.android.files.util.isMediaMetadataRetrieverCompatible
import me.zhanghai.android.files.util.logWarning
import me.zhanghai.android.files.util.runWithCancellationSignal
import me.zhanghai.android.files.util.setDataSource
import me.zhanghai.android.files.util.setDataSource as appSetDataSource
import me.zhanghai.android.files.util.valueCompat
import okio.buffer
import okio.source

class PathAttributesKeyer : Keyer<Pair<Path, BasicFileAttributes>> {
    override fun key(data: Pair<Path, BasicFileAttributes>, options: Options): String {
        val (path, attributes) = data
        // A thumbnail decoded for a list icon is too small for a grid cell, so the size is part of
        // the key, rounded so that sizes a few pixels apart still share an entry.
        val (width, height) = options.size
        val size = if (width is Dimension.Pixels && height is Dimension.Pixels) {
            "${RemoteThumbnails.roundSize(width.px)}x${RemoteThumbnails.roundSize(height.px)}"
        } else {
            "original"
        }
        return "$path:${attributes.lastModifiedInstant.toEpochMilli()}:${attributes.size()}:$size"
    }
}

class PathAttributesFetcher(
    private val data: Pair<Path, BasicFileAttributes>,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val appIconFetcherFactory: AppIconFetcher.Factory<Path>,
    private val pdfPageFetcherFactory: PdfPageFetcher.Factory<Path>
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val (path, attributes) = data
        val (width, height) = options.size
        // A grid cell on a dense screen is larger than MediaStore's MINI_SIZE, but still a
        // thumbnail.
        val isThumbnail = width is Dimension.Pixels && width.px <= RemoteThumbnails.MAX_SIZE_PX &&
            height is Dimension.Pixels && height.px <= RemoteThumbnails.MAX_SIZE_PX
        if (isThumbnail) {
            if (path.isDocumentPath && attributes.documentSupportsThumbnail) {
                val thumbnail = runWithCancellationSignal { signal ->
                    try {
                        path.getDocumentThumbnail(width.px, height.px, signal)
                    } catch (e: IOException) {
                        e.logWarning(TAG, "Get the document thumbnail of $path")
                        null
                    }
                }
                if (thumbnail != null) {
                    return DrawableResult(
                        thumbnail.toDrawable(options.context.resources),
                        true,
                        path.dataSource
                    )
                }
            }
            if (path.isRemotePath) {
                // FTP doesn't support random access and requires one connection per parallel read.
                val shouldReadRemotePath = !path.isFtpPath &&
                    Settings.READ_REMOTE_FILES_FOR_THUMBNAIL.valueCompat
                if (!shouldReadRemotePath) {
                    error("Cannot read $path for thumbnail")
                }
            }
        }
        if (!(path.isRemotePath && isThumbnail)) {
            return fetchFile(null, options)
        }
        // Rounded up, so that a list, a grid and a rotated grid mostly share what is on disk.
        val cacheWidth = RemoteThumbnails.roundSize(width.px)
        val cacheHeight = RemoteThumbnails.roundSize(height.px)
        val key = RemoteThumbnails.createKey(path, attributes, cacheWidth, cacheHeight)
        if (options.parameters.value<Boolean>(RemoteThumbnails.PARAMETER_PREVIEW) == true) {
            return fetchPreview(key)
        }
        RemoteThumbnails.get(key)?.let { return it }
        RemoteThumbnails.checkNotUnreadable(path, attributes)
        return RemoteThumbnails.withReadPermit {
            val cacheOptions = options.copy(size = Size(cacheWidth, cacheHeight))
            val drawable = try {
                when (val result = fetchFile(maxOf(width.px, height.px), cacheOptions)) {
                    is DrawableResult -> result.drawable
                    is SourceResult -> decode(result, cacheOptions)
                    null -> null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // The server may well answer next time.
                throw e
            } catch (e: Exception) {
                RemoteThumbnails.markUnreadable(path, attributes)
                throw e
            }
            if (drawable == null) {
                RemoteThumbnails.markUnreadable(path, attributes)
                return@withReadPermit null
            }
            RemoteThumbnails.put(key, drawable)
            DrawableResult(drawable, true, path.dataSource)
        }
    }

    /**
     * Returns the thumbnail a camera embedded in a JPEG however small it is, to show while the
     * sharp one is read. Fails when there is nothing to gain: the sharp one is on disk already, or
     * there is no embedded thumbnail.
     */
    private suspend fun fetchPreview(key: String): FetchResult {
        val (path, attributes) = data
        val mimeType = AndroidFileTypeDetector.getMimeType(path, attributes).asMimeType()
        check(mimeType == MimeType.IMAGE_JPEG) { "No preview for $mimeType" }
        check(!RemoteThumbnails.contains(key)) { "The thumbnail of $path is on disk" }
        RemoteThumbnails.checkNotUnreadable(path, attributes)
        val thumbnail = RemoteThumbnails.withPreviewPermit {
            runInterruptible { path.readExifThumbnail(0) }
        } ?: error("No embedded thumbnail in $path")
        return DrawableResult(
            thumbnail.toDrawable(options.context.resources),
            true,
            path.dataSource
        )
    }

    /**
     * Decodes right away what would otherwise be decoded after this fetcher has returned, because
     * only the decoded thumbnail is worth keeping on disk.
     */
    private suspend fun decode(result: SourceResult, options: Options): Drawable? {
        val decoder = imageLoader.components.newDecoder(result, options, imageLoader)?.first
        if (decoder == null) {
            result.source.closeSafe()
            return null
        }
        // Coil closes the source of a result it is given, but it never sees this one.
        return try {
            decoder.decode()?.drawable
        } finally {
            result.source.closeSafe()
        }
    }

    /**
     * @param remoteThumbnailSizePx the larger side of the view in pixels, when fetching a thumbnail
     * of a remote file
     */
    private suspend fun fetchFile(remoteThumbnailSizePx: Int?, options: Options): FetchResult? {
        val path = data.first
        val mimeType = AndroidFileTypeDetector.getMimeType(data.first, data.second).asMimeType()
        when {
            mimeType.isApk && path.isGetPackageArchiveInfoCompatible -> {
                try {
                    return appIconFetcherFactory.create(path, options, imageLoader).fetch()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.logWarning(TAG, "Load the app icon of $path")
                }
            }

            mimeType.isImage || mimeType == MimeType.GENERIC -> {
                if (remoteThumbnailSizePx != null && mimeType == MimeType.IMAGE_JPEG) {
                    val thumbnail = try {
                        runInterruptible { path.readExifThumbnail(remoteThumbnailSizePx) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.logWarning(TAG, "Read the embedded thumbnail of $path")
                        null
                    }
                    currentCoroutineContext().ensureActive()
                    if (thumbnail != null) {
                        return DrawableResult(
                            thumbnail.toDrawable(options.context.resources),
                            true,
                            path.dataSource
                        )
                    }
                }
                val inputStream = path.newInputStream()
                return SourceResult(
                    ImageSource(inputStream.source().buffer(), options.context),
                    if (mimeType != MimeType.GENERIC) mimeType.value else null,
                    path.dataSource
                )
            }

            mimeType.isMedia && path.isMediaMetadataRetrieverCompatible -> {
                try {
                    return fetchMedia(path, mimeType.isVideo, options)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.logWarning(TAG, "Read the picture or a frame of $path")
                }
                currentCoroutineContext().ensureActive()
            }

            mimeType.isPdf && (path.isLinuxPath || path.isDocumentPath) -> {
                try {
                    return pdfPageFetcherFactory.create(path, options, imageLoader).fetch()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.logWarning(TAG, "Render the first page of $path")
                }
            }
        }
        return null
    }

    /**
     * Asks one retriever for the embedded picture and then for a video frame, so that a file on a
     * server is opened and its header parsed only once.
     */
    private suspend fun fetchMedia(path: Path, isVideo: Boolean, options: Options): FetchResult? =
        runAbortable { abortHandle ->
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(path) { abortHandle.set(it) }
                val embeddedPicture = retriever.embeddedPicture
                when {
                    embeddedPicture != null ->
                        SourceResult(
                            ImageSource(
                                embeddedPicture.inputStream().source().buffer(),
                                options.context
                            ),
                            null,
                            path.dataSource
                        )

                    isVideo -> retriever.decodeVideoFrame(options)

                    else -> null
                }
            }
        }

    companion object {
        private const val TAG = "PathAttributesFetcher"
    }

    class Factory(private val context: Context) : Fetcher.Factory<Pair<Path, BasicFileAttributes>> {
        private val appIconFetcherFactory = object : AppIconFetcher.Factory<Path>(
            // This is used by FileListAdapter.
            context.getDimensionPixelSize(R.dimen.large_icon_size),
            context
        ) {
            override fun getApplicationInfo(data: Path): Pair<ApplicationInfo, Closeable?> {
                val (packageInfo, closeable) =
                    context.packageManager.getPackageArchiveInfoCompat(data, 0)
                val applicationInfo = packageInfo?.applicationInfo
                if (applicationInfo == null) {
                    closeable?.close()
                    throw IOException("ApplicationInfo is null")
                }
                return applicationInfo to closeable
            }
        }

        private val pdfPageFetcherFactory = object : PdfPageFetcher.Factory<Path>() {
            override fun openParcelFileDescriptor(data: Path): ParcelFileDescriptor = when {
                data.isLinuxPath ->
                    ParcelFileDescriptor.open(
                        data.toFile(),
                        ParcelFileDescriptor.MODE_READ_ONLY
                    )

                data.isDocumentPath -> data.openDocumentParcelFileDescriptor("r")

                else -> throw IllegalArgumentException(data.toString())
            }
        }

        override fun create(
            data: Pair<Path, BasicFileAttributes>,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher = PathAttributesFetcher(
            data,
            options,
            imageLoader,
            appIconFetcherFactory,
            pdfPageFetcherFactory
        )
    }
}
