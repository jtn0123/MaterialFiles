/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

/**
 * The span from the first to the last selected position among [itemCount] items, or null when
 * fewer than two positions are selected and there is nothing between them to select.
 */
fun selectionRange(itemCount: Int, isSelected: (Int) -> Boolean): IntRange? {
    val first = (0..<itemCount).firstOrNull(isSelected) ?: return null
    val last = (itemCount - 1 downTo first).first(isSelected)
    return if (last > first) first..last else null
}
