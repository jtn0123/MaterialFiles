/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import android.view.View
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.fileSize
import me.zhanghai.android.files.file.formatShort

/** Names the row and its menu button for TalkBack, since the type and the badges are only drawn. */
internal fun FileListAdapter.ViewHolder.bindAccessibilityDescription(file: FileItem) {
    val context = itemLayout.context
    itemLayout.contentDescription = file.getSpokenDescription(ContextSpokenStrings(context))
    menuButton.contentDescription = context.getString(R.string.file_item_menu_format, file.name)
}

/** Announces the selection state, and names what a tap and a long press on the row do now. */
internal fun FileListAdapter.ViewHolder.bindAccessibilityState(
    file: FileItem,
    isSelectable: Boolean,
    isSelected: Boolean,
    isSelecting: Boolean
) {
    val context = itemLayout.context
    val stateRes = getFileItemSelectionStateRes(isSelected, isSelecting)
    ViewCompat.setStateDescription(itemLayout, stateRes?.let { context.getString(it) })
    val labels = getFileItemActionLabels(isSelectable, isSelected, isSelecting)
    itemLayout.relabelAccessibilityAction(AccessibilityActionCompat.ACTION_CLICK, labels.click)
    itemLayout.relabelAccessibilityAction(
        AccessibilityActionCompat.ACTION_LONG_CLICK,
        labels.longClick
    )
    iconLayout.apply {
        importantForAccessibility = if (isSelectable) {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val formatRes =
            if (isSelected) R.string.file_item_deselect_format else R.string.file_item_select_format
        contentDescription = context.getString(formatRes, file.name)
    }
}

/** A null label puts back the default name; the action itself is left to the view. */
private fun View.relabelAccessibilityAction(
    action: AccessibilityActionCompat,
    @StringRes labelRes: Int?
) {
    val label = labelRes?.let { context.getString(it) }
    ViewCompat.replaceAccessibilityAction(this, action, label, null)
}

private class ContextSpokenStrings(private val context: Context) : FileItemSpokenStrings {
    override val separator: String =
        context.getString(R.string.file_item_accessibility_separator)

    override fun getTypeName(file: FileItem): String = file.getMimeTypeName(context)

    override fun formatLastModified(file: FileItem): String =
        file.attributes.lastModifiedTime().toInstant().formatShort(context)

    override fun formatSize(file: FileItem): String =
        file.attributes.fileSize.formatHumanReadable(context)

    override fun getFlagName(flag: FileItemSpokenFlag): String = context.getString(
        when (flag) {
            FileItemSpokenFlag.SYMBOLIC_LINK -> R.string.file_item_accessibility_symbolic_link

            FileItemSpokenFlag.BROKEN_SYMBOLIC_LINK ->
                R.string.file_item_accessibility_broken_symbolic_link

            FileItemSpokenFlag.ENCRYPTED -> R.string.file_item_accessibility_encrypted

            FileItemSpokenFlag.APP_DIRECTORY -> R.string.file_item_accessibility_app_directory
        }
    )
}
