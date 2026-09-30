/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import coil.size.Precision
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.DefaultOnImageEventListener
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.coil.RemoteThumbnails
import me.zhanghai.android.files.coil.fadeIn
import me.zhanghai.android.files.databinding.ImageViewerItemBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.filelist.isRemotePath
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.fadeInUnsafe
import me.zhanghai.android.files.util.fadeOutUnsafe
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.logWarning
import me.zhanghai.android.files.util.shortAnimTime
import me.zhanghai.android.files.util.toUserMessage

class ImageViewerAdapter(
    private val lifecycleOwner: LifecycleOwner,
    // Reading an image blocks, so it never runs on the thread that shows it.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val listener: (View) -> Unit
) : SimpleAdapter<Path, ImageViewerAdapter.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long = getItem(position).hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ImageViewerItemBinding.inflate(parent.context.layoutInflater, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val path = getItem(position)
        val binding = holder.binding
        binding.image.setOnPhotoTapListener { view, _, _ -> listener(view ?: binding.image) }
        binding.largeImage.setOnClickListener(listener)
        loadImage(binding, path)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)

        val binding = holder.binding
        binding.image.dispose()
        binding.largeImage.recycle()
    }

    private fun loadImage(binding: ImageViewerItemBinding, path: Path) {
        // A holder is rebound to another photo while a read for the one before can still finish.
        val load = Any()
        binding.root.setTag(R.id.image_viewer_load, load)
        val isCurrent = { binding.root.getTag(R.id.image_viewer_load) === load }
        binding.progress.fadeInUnsafe(true)
        binding.errorLayout.fadeOutUnsafe()
        binding.image.isVisible = false
        binding.image.setImageDrawable(null)
        binding.largeImage.isVisible = false
        lifecycleOwner.lifecycleScope.launch {
            val isRemote = path.isRemotePath
            val imageInfo = try {
                val attributes =
                    withContext(ioDispatcher) {
                        path.readAttributes(BasicFileAttributes::class.java)
                    }
                // What the grid showed is on disk, and is shown while the photo itself is read.
                val cachedThumbnail = if (isRemote) {
                    withContext(ioDispatcher) {
                        RemoteThumbnails.readCachedBitmap(path, attributes)
                    }
                } else {
                    null
                }
                if (cachedThumbnail != null && isCurrent()) {
                    showPlaceholder(binding, cachedThumbnail)
                }
                withContext(ioDispatcher) {
                    path.readImageInfo(attributes, isRemote && cachedThumbnail == null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.logWarning("ImageViewerAdapter", "Load the image info of $path")
                if (isCurrent()) {
                    showError(binding, path, e)
                }
                return@launch
            }
            if (!isCurrent()) {
                return@launch
            }
            imageInfo.preview?.let { showPlaceholder(binding, it) }
            loadImageWithInfo(binding, path, imageInfo)
        }
    }

    private fun showPlaceholder(binding: ImageViewerItemBinding, bitmap: Bitmap) {
        binding.image.apply {
            setImageDrawable(bitmap.toDrawable(resources))
            isVisible = true
        }
    }

    private fun loadImageWithInfo(
        binding: ImageViewerItemBinding,
        path: Path,
        imageInfo: ImageInfo
    ) {
        if (!imageInfo.shouldUseLargeImageView) {
            binding.image.apply {
                val placeholder = drawable
                isVisible = true
                val displayMetrics = resources.displayMetrics
                val (width, height) = getViewerDecodeSize(
                    imageInfo.width,
                    imageInfo.height,
                    imageInfo.rotationDegrees,
                    displayMetrics.widthPixels,
                    displayMetrics.heightPixels
                )
                load(path to imageInfo.attributes) {
                    // Larger than any thumbnail, so that it is never mistaken for one.
                    size(
                        width.coerceAtLeast(RemoteThumbnails.MAX_SIZE_PX + 1),
                        height.coerceAtLeast(RemoteThumbnails.MAX_SIZE_PX + 1)
                    )
                    // Never scaled up to fill the box, only down.
                    precision(Precision.INEXACT)
                    fadeIn(context.shortAnimTime)
                    // After fadeIn(), which sets a transparent one of its own.
                    placeholder(placeholder)
                    listener(
                        onSuccess = { _, _ -> binding.progress.fadeOutUnsafe() },
                        onError = { _, result -> showError(binding, path, result.throwable) }
                    )
                }
            }
        } else {
            binding.largeImage.apply {
                setDoubleTapZoomDuration(300)
                orientation = SubsamplingScaleImageView.ORIENTATION_USE_EXIF
                // Otherwise OnImageEventListener.onReady() is never called.
                isVisible = true
                alpha = 0f
                setOnImageEventListener(object : DefaultOnImageEventListener() {
                    override fun onReady() {
                        setDoubleTapZoomScale(binding.largeImage.cropScale)
                        binding.progress.fadeOutUnsafe()
                        binding.largeImage.fadeInUnsafe(true)
                        // The placeholder, if there was one, has done its job.
                        binding.image.isVisible = false
                        binding.image.setImageDrawable(null)
                    }

                    override fun onImageLoadError(e: Exception) {
                        e.logWarning("ImageViewerAdapter", "Load the large image $path")
                        showError(binding, path, e)
                    }
                })
                setImageRestoringSavedState(ImageSource.uri(path.fileProviderUri))
            }
        }
    }

    private val ImageInfo.shouldUseLargeImageView: Boolean
        get() {
            // See BitmapFactory.cpp encodedFormatToString()
            if (mimeType == MimeType.IMAGE_GIF) {
                return false
            }
            if (width <= 0 || height <= 0) {
                return false
            }
            // 4 bytes per pixel for ARGB_8888.
            if (width * height * 4 > MAX_BITMAP_SIZE) {
                return true
            }
            if (width > 2048 || height > 2048) {
                val ratio = width.toFloat() / height
                if (ratio < 0.5 || ratio > 2) {
                    return true
                }
            }
            return false
        }

    private val SubsamplingScaleImageView.cropScale: Float
        get() {
            val viewWidth = (width - paddingLeft - paddingRight)
            val viewHeight = (height - paddingTop - paddingBottom)
            val orientation = appliedOrientation
            val rotated90Or270 = orientation == SubsamplingScaleImageView.ORIENTATION_90 ||
                orientation == SubsamplingScaleImageView.ORIENTATION_270
            val imageWidth = if (rotated90Or270) sHeight else sWidth
            val imageHeight = if (rotated90Or270) sWidth else sHeight
            return max(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        }

    private fun showError(binding: ImageViewerItemBinding, path: Path, throwable: Throwable) {
        binding.progress.fadeOutUnsafe()
        binding.errorText.text = throwable.toUserMessage(binding.errorText.context)
        // A remote image often fails only because the connection dropped for a moment.
        binding.retryButton.setOnClickListener { loadImage(binding, path) }
        binding.errorLayout.fadeInUnsafe(true)
        binding.image.isVisible = false
        binding.largeImage.isVisible = false
    }

    companion object {
        // @see android.graphics.RecordingCanvas#MAX_BITMAP_SIZE
        private const val MAX_BITMAP_SIZE = 100 * 1024 * 1024
    }

    class ViewHolder(val binding: ImageViewerItemBinding) : RecyclerView.ViewHolder(binding.root)
}
