/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import androidx.annotation.StringRes
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.provider.common.isEncrypted

/** What a file list row shows only as a badge, and so has to be said aloud instead. */
internal enum class FileItemSpokenFlag {
    SYMBOLIC_LINK,
    BROKEN_SYMBOLIC_LINK,
    ENCRYPTED,
    APP_DIRECTORY
}

/** The words a file list row is read aloud with; see [getSpokenDescription]. */
internal interface FileItemSpokenStrings {
    val separator: String

    fun getTypeName(file: FileItem): String

    fun formatLastModified(file: FileItem): String

    fun formatSize(file: FileItem): String

    fun getFlagName(flag: FileItemSpokenFlag): String
}

internal val FileItem.spokenFlags: List<FileItemSpokenFlag>
    get() = buildList {
        if (attributesNoFollowLinks.isSymbolicLink) {
            add(
                if (isSymbolicLinkBroken) {
                    FileItemSpokenFlag.BROKEN_SYMBOLIC_LINK
                } else {
                    FileItemSpokenFlag.SYMBOLIC_LINK
                }
            )
        }
        if (attributesNoFollowLinks.isEncrypted()) {
            add(FileItemSpokenFlag.ENCRYPTED)
        }
        if (appDirectoryPackageName != null) {
            add(FileItemSpokenFlag.APP_DIRECTORY)
        }
    }

/**
 * The content description of a file list row: its name, its type, what its badge means, and for a
 * file the modification time and size that the row shows under the name.
 */
internal fun FileItem.getSpokenDescription(strings: FileItemSpokenStrings): String {
    val flags = spokenFlags
    val parts = mutableListOf(name)
    // Nothing is known about what a broken link points to, so its type would only repeat the flag.
    if (FileItemSpokenFlag.BROKEN_SYMBOLIC_LINK !in flags) {
        parts += strings.getTypeName(this)
    }
    flags.mapTo(parts) { strings.getFlagName(it) }
    if (!attributes.isDirectory) {
        parts += strings.formatLastModified(this)
        parts += strings.formatSize(this)
    }
    return parts.joinToString(strings.separator)
}

/** The selection state of a row, announced only while there is a selection. */
@StringRes
internal fun getFileItemSelectionStateRes(isSelected: Boolean, isSelecting: Boolean): Int? = when {
    isSelected -> R.string.file_item_state_selected
    isSelecting -> R.string.file_item_state_not_selected
    else -> null
}

/**
 * What a tap and a long press on a row do, as TalkBack names them; null leaves an action with its
 * default name.
 */
internal class FileItemActionLabels(@StringRes val click: Int?, @StringRes val longClick: Int?)

/** @see FileListAdapter.onItemClick and FileListAdapter.onItemLongClick */
internal fun getFileItemActionLabels(
    isSelectable: Boolean,
    isSelected: Boolean,
    isSelecting: Boolean
): FileItemActionLabels {
    val selectRes = if (isSelected) R.string.file_item_action_deselect else R.string.select
    return if (!isSelecting) {
        FileItemActionLabels(
            R.string.file_item_action_open,
            if (isSelectable) selectRes else null
        )
    } else {
        FileItemActionLabels(
            if (isSelectable) selectRes else null,
            R.string.file_item_action_open
        )
    }
}
