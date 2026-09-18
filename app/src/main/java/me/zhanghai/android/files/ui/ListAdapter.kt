/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import androidx.recyclerview.widget.AdapterListUpdateCallback
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

abstract class ListAdapter<T, VH : RecyclerView.ViewHolder>(
    callback: DiffUtil.ItemCallback<T>,
    asyncDiff: Boolean = false
) : RecyclerView.Adapter<VH>() {
    private val listDiffer = ListDiffer(
        AdapterListUpdateCallback(this),
        callback,
        ::onListChanged,
        if (asyncDiff) {
            me.zhanghai.android.files.util.backgroundExecutor
        } else {
            java.util.concurrent.Executor {
                it.run()
            }
        },
        if (asyncDiff) {
            me.zhanghai.android.files.app.mainExecutor
        } else {
            java.util.concurrent.Executor {
                it.run()
            }
        }
    )

    val list: List<T>
        get() = listDiffer.list

    override fun getItemCount(): Int = list.size

    fun getItem(position: Int): T = list[position]

    // Disable stable IDs and only let the list callback instruct animation properly.
    final override fun getItemId(position: Int): Long = RecyclerView.NO_ID

    open fun refresh() {
        val list = listDiffer.list
        listDiffer.submit(emptyList())
        listDiffer.submit(list)
    }

    open fun replace(list: List<T>, clear: Boolean, committed: () -> Unit = {}) {
        if (clear) {
            listDiffer.submit(emptyList())
        }
        listDiffer.submit(list, committed)
    }

    protected open fun onListChanged() {}

    open fun clear() {
        listDiffer.submit(emptyList())
    }
}
