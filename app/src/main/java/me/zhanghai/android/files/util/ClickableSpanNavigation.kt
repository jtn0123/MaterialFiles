/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import kotlin.math.max
import kotlin.math.min

/**
 * The selection from anchor [a] to focus [b] as the ordered range the arrow keys move from, given
 * that only the text between [first] and [last] is visible: nothing selected yet is the end of the
 * text if the focus came [fromBelow], and a selection scrolled out of sight is treated as being
 * past that end of the visible text.
 */
internal fun navigationSelection(
    a: Int,
    b: Int,
    fromBelow: Boolean,
    textLength: Int,
    first: Int,
    last: Int
): Pair<Int, Int> {
    var selectionStart = min(a, b)
    var selectionEnd = max(a, b)
    if (selectionStart < 0 && fromBelow) {
        selectionStart = textLength
        selectionEnd = textLength
    }
    if (selectionStart > last) {
        selectionStart = Int.MAX_VALUE
        selectionEnd = Int.MAX_VALUE
    }
    if (selectionEnd < first) {
        selectionStart = -1
        selectionEnd = -1
    }
    return selectionStart to selectionEnd
}

/**
 * Of the spans with the given start and end [bounds], the one ending last before the selection, or
 * the one ending last of all if nothing is selected.
 */
internal fun previousSpanBounds(
    bounds: List<Pair<Int, Int>>,
    selectionStart: Int,
    selectionEnd: Int
): Pair<Int, Int>? {
    val isSelectionEmpty = selectionStart == selectionEnd
    return bounds.filter { (_, end) -> end < selectionEnd || isSelectionEmpty }
        .maxByOrNull { (_, end) -> end }
}

/**
 * Of the spans with the given start and end [bounds], the one starting first after the start of
 * the selection, or the one starting first of all if nothing is selected.
 */
internal fun nextSpanBounds(
    bounds: List<Pair<Int, Int>>,
    selectionStart: Int,
    selectionEnd: Int
): Pair<Int, Int>? {
    val isSelectionEmpty = selectionStart == selectionEnd
    return bounds.filter { (start, _) -> start > selectionStart || isSelectionEmpty }
        .minByOrNull { (start, _) -> start }
}
