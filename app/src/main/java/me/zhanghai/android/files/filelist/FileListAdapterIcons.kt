/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.core.view.isVisible
import coil.dispose
import coil.imageLoader
import coil.load
import coil.request.Disposable
import coil.request.ImageRequest
import coil.size.ViewSizeResolver
import java.io.IOException
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.R
import me.zhanghai.android.files.coil.AppIconPackageName
import me.zhanghai.android.files.coil.RemoteThumbnails
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.iconRes
import me.zhanghai.android.files.file.isApk
import me.zhanghai.android.files.provider.common.isEncrypted
import me.zhanghai.android.files.util.logWarning

/** Binds the icon, thumbnail and badges of a file list item. */
internal fun FileListAdapter.ViewHolder.bindIcons(file: FileItem) {
    val path = file.path
    val isDirectory = file.attributes.isDirectory
    val iconRes = file.mimeType.iconRes
    iconImage.apply {
        isVisible = true
        setImageResource(iconRes)
    }
    directoryThumbnailImage?.isVisible = isDirectory
    thumbnailOutlineView?.isVisible = !isDirectory
    val supportsThumbnail = file.supportsThumbnail
    val shouldLoadThumbnailIcon = supportsThumbnail && thumbnailIconImage != null &&
        file.mimeType.isApk
    val attributes = file.attributes
    thumbnailIconImage?.apply {
        dispose()
        isVisible = !isDirectory
        setImageResource(iconRes)
        if (shouldLoadThumbnailIcon) {
            load(path to attributes)
        }
    }
    thumbnailImage.apply {
        dispose()
        (getTag(R.id.thumbnail_load) as ThumbnailLoad?)?.preview?.dispose()
        setTag(R.id.thumbnail_load, null)
        setImageDrawable(null)
        val shouldLoadThumbnail = supportsThumbnail && !shouldLoadThumbnailIcon
        isVisible = shouldLoadThumbnail
        if (shouldLoadThumbnail) {
            val thumbnailLoad = ThumbnailLoad(path to attributes)
            setTag(R.id.thumbnail_load, thumbnailLoad)
            val onShown = {
                val iconImage = thumbnailIconImage ?: iconImage
                iconImage.isVisible = false
            }
            // A grid cell is too large for the thumbnail a camera embeds, so the sharp one means
            // reading the whole photo; the embedded one fills the cell in the meantime.
            val isGrid = directoryThumbnailImage != null
            if (isGrid && path.isRemotePath && file.mimeType == MimeType.IMAGE_JPEG) {
                thumbnailLoad.preview = loadThumbnailPreview(thumbnailLoad, onShown)
            }
            loadThumbnail(thumbnailLoad, false, onShown)
        }
    }
    appIconBadgeImage.apply {
        dispose()
        setImageDrawable(null)
        val appDirectoryPackageName = file.appDirectoryPackageName
        val hasAppIconBadge = appDirectoryPackageName != null
        isVisible = hasAppIconBadge
        if (hasAppIconBadge) {
            load(AppIconPackageName(appDirectoryPackageName))
        }
    }
    badgeImage.apply {
        val badgeIconRes = if (file.attributesNoFollowLinks.isSymbolicLink) {
            if (file.isSymbolicLinkBroken) {
                R.drawable.error_badge_icon_18dp
            } else {
                R.drawable.symbolic_link_badge_icon_18dp
            }
        } else if (file.attributesNoFollowLinks.isEncrypted()) {
            R.drawable.encrypted_badge_icon_18dp
        } else {
            null
        }
        val hasBadge = badgeIconRes != null
        isVisible = hasBadge
        if (hasBadge) {
            setImageResource(badgeIconRes)
        } else {
            setImageDrawable(null)
        }
    }
}

/** The thumbnail a view is loading, so that a callback for a file it no longer shows is dropped. */
private class ThumbnailLoad(val data: Pair<Path, BasicFileAttributes>) {
    var preview: Disposable? = null
    var previewDrawable: Drawable? = null
}

private fun ImageView.isLoading(thumbnailLoad: ThumbnailLoad): Boolean =
    getTag(R.id.thumbnail_load) === thumbnailLoad

private fun ImageView.loadThumbnailPreview(
    thumbnailLoad: ThumbnailLoad,
    onShown: () -> Unit
): Disposable {
    val request = ImageRequest.Builder(context)
        .data(thumbnailLoad.data)
        .size(ViewSizeResolver(this))
        .setParameter(RemoteThumbnails.PARAMETER_PREVIEW, true)
        .target(
            onSuccess = { drawable ->
                // The sharp one may have been quicker.
                if (isLoading(thumbnailLoad) && this.drawable == null) {
                    thumbnailLoad.previewDrawable = drawable
                    setImageDrawable(drawable)
                    onShown()
                }
            }
        )
        .build()
    return context.imageLoader.enqueue(request)
}

private fun ImageView.loadThumbnail(
    thumbnailLoad: ThumbnailLoad,
    isRetry: Boolean,
    onShown: () -> Unit
) {
    load(thumbnailLoad.data) {
        placeholder(thumbnailLoad.previewDrawable)
        listener(
            onSuccess = { _, _ -> onShown() },
            onError = { _, result ->
                val throwable = result.throwable
                throwable.logWarning(TAG, "Load the thumbnail of ${thumbnailLoad.data.first}")
                if (isLoading(thumbnailLoad)) {
                    thumbnailLoad.previewDrawable?.let { setImageDrawable(it) }
                    // A server that timed out or dropped the connection may well answer now.
                    if (!isRetry && throwable is IOException) {
                        post {
                            if (isLoading(thumbnailLoad)) {
                                loadThumbnail(thumbnailLoad, true, onShown)
                            }
                        }
                    }
                }
            }
        )
    }
}

private const val TAG = "FileListAdapterIcons"
