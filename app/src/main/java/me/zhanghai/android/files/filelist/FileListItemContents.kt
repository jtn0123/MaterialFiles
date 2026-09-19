/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.provider.common.isEncrypted
import me.zhanghai.android.files.provider.document.documentSupportsThumbnail
import me.zhanghai.android.files.provider.document.isDocumentPath

/**
 * Whether a file list row bound to [this] would look and behave the same bound to [other].
 *
 * [FileItem] equality can't be used for this: most attribute classes have no `equals()`, so every
 * re-list would rebind every row and restart its thumbnail. This compares everything the row and
 * its thumbnail read instead (see [FileListAdapter.onBindViewHolder] and [bindIcons]).
 */
internal fun FileItem.hasSameListContentsAs(other: FileItem): Boolean {
    if (this === other) {
        return true
    }
    if (path != other.path || isHidden != other.isHidden || mimeType != other.mimeType ||
        symbolicLinkTarget != other.symbolicLinkTarget
    ) {
        return false
    }
    val noFollowLinks = attributesNoFollowLinks
    val otherNoFollowLinks = other.attributesNoFollowLinks
    if (!noFollowLinks.hasSameListContentsAs(otherNoFollowLinks) ||
        noFollowLinks.isEncrypted() != otherNoFollowLinks.isEncrypted()
    ) {
        return false
    }
    if (noFollowLinks.isSymbolicLink && isSymbolicLinkBroken != other.isSymbolicLinkBroken) {
        return false
    }
    if (!attributes.hasSameListContentsAs(other.attributes)) {
        return false
    }
    if (path.isDocumentPath &&
        attributes.documentSupportsThumbnail != other.attributes.documentSupportsThumbnail
    ) {
        return false
    }
    return true
}

private fun BasicFileAttributes.hasSameListContentsAs(other: BasicFileAttributes): Boolean =
    this === other || (
        isDirectory == other.isDirectory &&
            isRegularFile == other.isRegularFile &&
            isSymbolicLink == other.isSymbolicLink &&
            isOther == other.isOther &&
            size() == other.size() &&
            lastModifiedTime() == other.lastModifiedTime()
        )
