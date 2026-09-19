/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import androidx.core.view.isVisible
import coil.dispose
import coil.load
import me.zhanghai.android.files.R
import me.zhanghai.android.files.coil.AppIconPackageName
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.iconRes
import me.zhanghai.android.files.file.isApk
import me.zhanghai.android.files.provider.common.isEncrypted

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
        setImageDrawable(null)
        val shouldLoadThumbnail = supportsThumbnail && !shouldLoadThumbnailIcon
        isVisible = shouldLoadThumbnail
        if (shouldLoadThumbnail) {
            load(path to attributes) {
                listener { _, _ ->
                    val iconImage = thumbnailIconImage ?: iconImage
                    iconImage.isVisible = false
                }
            }
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
