/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import androidx.annotation.IdRes
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem

/** Runs the action of the item [itemId] in a file's row menu, and returns whether there was one. */
internal fun FileListAdapter.Listener.onFileItemMenuItemClick(
    @IdRes itemId: Int,
    file: FileItem
): Boolean {
    val action: (FileItem) -> Unit = when (itemId) {
        R.id.action_open_with -> ::openFileWith
        R.id.action_cut -> ::cutFile
        R.id.action_copy -> ::copyFile
        R.id.action_delete -> ::confirmDeleteFile
        R.id.action_rename -> ::showRenameFileDialog
        R.id.action_extract -> ::extractFile
        R.id.action_archive -> ::showCreateArchiveDialog
        R.id.action_share -> ::shareFile
        R.id.action_copy_path -> ::copyPath
        R.id.action_add_bookmark -> ::addBookmark
        R.id.action_create_shortcut -> ::createShortcut
        R.id.action_properties -> ::showPropertiesDialog
        else -> return false
    }
    action(file)
    return true
}
