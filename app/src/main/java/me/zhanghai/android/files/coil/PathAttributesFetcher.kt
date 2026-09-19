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
import me.zhanghai.android.files.util.runWithCancellationSignal
import me.zhanghai.android.files.util.setDataSource
import me.zhanghai.android.files.util.setDataSource as appSetDataSource
import me.zhanghai.android.files.util.valueCompat
import okio.buffer
import okio.source

class PathAttributesKeyer : Keyer<Pair<Path, BasicFileAttributes>> {
    override fun key(data: Pair<Path, BasicFileAttributes>, options: Options): String {
        val (path, attributes) = data
        return "$path:${attributes.lastModifiedInstant.toEpochMilli()}"
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
        // @see android.provider.MediaStore.ThumbnailConstants.MINI_SIZE
        val isThumbnail = width is Dimension.Pixels && width.px <= 512 &&
            height is Dimension.Pixels && height.px <= 384
        if (isThumbnail) {
            width as Dimension.Pixels
            height as Dimension.Pixels
            if (path.isDocumentPath && attributes.documentSupportsThumbnail) {
                val thumbnail = runWithCancellationSignal { signal ->
                    try {
                        path.getDocumentThumbnail(width.px, height.px, signal)
                    } catch (e: IOException) {
                        e.printStackTrace()
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
        val isRemoteThumbnail = path.isRemotePath &&
            width is Dimension.Pixels && width.px <= RemoteThumbnails.MAX_SIZE_PX &&
            height is Dimension.Pixels && height.px <= RemoteThumbnails.MAX_SIZE_PX
        if (!isRemoteThumbnail) {
            return fetchFile(null)
        }
        width as Dimension.Pixels
        height as Dimension.Pixels
        val key = RemoteThumbnails.createKey(path, attributes, width.px, height.px)
        RemoteThumbnails.get(key)?.let { return it }
        return RemoteThumbnails.withReadPermit {
            val drawable = when (val result = fetchFile(width.px to height.px)) {
                is DrawableResult -> result.drawable
                is SourceResult -> decode(result)
                null -> null
            } ?: return@withReadPermit null
            RemoteThumbnails.put(key, drawable)
            DrawableResult(drawable, true, path.dataSource)
        }
    }

    /**
     * Decodes right away what would otherwise be decoded after this fetcher has returned, because
     * only the decoded thumbnail is worth keeping on disk.
     */
    private suspend fun decode(result: SourceResult): Drawable? {
        val decoder = imageLoader.components.newDecoder(result, options, imageLoader)?.first
        if (decoder == null) {
            result.source.closeSafe()
            return null
        }
        return decoder.decode()?.drawable
    }

    /** @param remoteThumbnailSize the size in pixels, when fetching a thumbnail of a remote file */
    private suspend fun fetchFile(remoteThumbnailSize: Pair<Int, Int>?): FetchResult? {
        val path = data.first
        val mimeType = AndroidFileTypeDetector.getMimeType(data.first, data.second).asMimeType()
        when {
            mimeType.isApk && path.isGetPackageArchiveInfoCompatible -> {
                try {
                    return appIconFetcherFactory.create(path, options, imageLoader).fetch()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            mimeType.isImage || mimeType == MimeType.GENERIC -> {
                if (remoteThumbnailSize != null && mimeType == MimeType.IMAGE_JPEG) {
                    val (width, height) = remoteThumbnailSize
                    val thumbnail = try {
                        runInterruptible { path.readExifThumbnail(width, height) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.printStackTrace()
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
                    return fetchMedia(path, mimeType.isVideo)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                currentCoroutineContext().ensureActive()
            }

            mimeType.isPdf && (path.isLinuxPath || path.isDocumentPath) -> {
                try {
                    return pdfPageFetcherFactory.create(path, options, imageLoader).fetch()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return null
    }

    /**
     * Asks one retriever for the embedded picture and then for a video frame, so that a file on a
     * server is opened and its header parsed only once.
     */
    private suspend fun fetchMedia(path: Path, isVideo: Boolean): FetchResult? =
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
