/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

/**
 * Picks the largest window of [items] around [position] whose total [cost] stays within [budget],
 * growing alternately to each side, and returns it with the new position of the item.
 *
 * The viewers get their sibling list through an intent, and a binder transaction is capped at
 * 1 MB, so the list has to be bounded by its size rather than by its length: a remote path can be
 * many times longer than a local one. The item at [position] is always included.
 */
fun <T> windowWithinBudget(
    items: List<T>,
    position: Int,
    budget: Int,
    cost: (T) -> Int
): Pair<List<T>, Int> {
    require(position in items.indices) { "position $position not in ${items.indices}" }
    var start = position
    var end = position + 1
    var total = cost(items[position])
    var canGrowStart = true
    var canGrowEnd = true
    while (canGrowStart || canGrowEnd) {
        if (canGrowEnd) {
            val itemCost = if (end < items.size) cost(items[end]) else null
            if (itemCost != null && total + itemCost <= budget) {
                total += itemCost
                ++end
            } else {
                canGrowEnd = false
            }
        }
        if (canGrowStart) {
            val itemCost = if (start > 0) cost(items[start - 1]) else null
            if (itemCost != null && total + itemCost <= budget) {
                total += itemCost
                --start
            } else {
                canGrowStart = false
            }
        }
    }
    return items.subList(start, end) to position - start
}
