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
    val window = BudgetWindow(items, position, budget, cost)
    var canGrowStart = true
    var canGrowEnd = true
    while (canGrowStart || canGrowEnd) {
        canGrowEnd = canGrowEnd && window.growEnd()
        canGrowStart = canGrowStart && window.growStart()
    }
    return items.subList(window.start, window.end) to position - window.start
}

/** The window `[start, end)` of [items] around a position, and what it costs so far. */
private class BudgetWindow<T>(
    private val items: List<T>,
    position: Int,
    private val budget: Int,
    private val cost: (T) -> Int
) {
    var start = position
        private set
    var end = position + 1
        private set
    private var total = cost(items[position])

    /** Adds the item after the window if it is within the budget, and returns whether it was. */
    fun growEnd(): Boolean {
        if (end == items.size || !fits(items[end])) {
            return false
        }
        ++end
        return true
    }

    /** Adds the item before the window if it is within the budget, and returns whether it was. */
    fun growStart(): Boolean {
        if (start == 0 || !fits(items[start - 1])) {
            return false
        }
        --start
        return true
    }

    private fun fits(item: T): Boolean {
        val itemCost = cost(item)
        if (total + itemCost > budget) {
            return false
        }
        total += itemCost
        return true
    }
}
