/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import java.util.concurrent.Executor
import me.zhanghai.android.files.app.mainExecutor
import me.zhanghai.android.files.util.backgroundExecutor

class ListDiffer<T>(
    private val updateCallback: ListUpdateCallback,
    private val diffCallback: DiffUtil.ItemCallback<T>,
    private val onChanged: () -> Unit = {},
    private val worker: Executor = backgroundExecutor,
    private val main: Executor = mainExecutor
) {
    private var generation = 0
    private var _list: List<T> = emptyList()
    val list: List<T> get() = _list

    fun submit(newList: List<T>, committed: () -> Unit = {}) {
        val request = ++generation
        if (newList === _list || (newList.isEmpty() && _list.isEmpty())) {
            committed()
            return
        }
        if (newList.isEmpty()) {
            val oldListSize = _list.size
            _list = emptyList()
            onChanged()
            updateCallback.onRemoved(0, oldListSize)
            committed()
            return
        }
        if (_list.isEmpty()) {
            _list = newList
            onChanged()
            updateCallback.onInserted(0, newList.size)
            committed()
            return
        }
        val oldList = _list
        worker.execute {
            val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldList.size

                override fun getNewListSize(): Int = newList.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val oldItem: T? = oldList[oldItemPosition]
                    val newItem: T? = newList[newItemPosition]
                    return if (oldItem != null && newItem != null) {
                        diffCallback.areItemsTheSame(oldItem, newItem)
                    } else {
                        oldItem == null && newItem == null
                    }
                }

                override fun areContentsTheSame(
                    oldItemPosition: Int,
                    newItemPosition: Int
                ): Boolean {
                    val oldItem: T? = oldList[oldItemPosition]
                    val newItem: T? = newList[newItemPosition]
                    return if (oldItem != null && newItem != null) {
                        diffCallback.areContentsTheSame(oldItem, newItem)
                    } else if (oldItem == null && newItem == null) {
                        true
                    } else {
                        throw AssertionError()
                    }
                }

                override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? {
                    val oldItem: T? = oldList[oldItemPosition]
                    val newItem: T? = newList[newItemPosition]
                    return if (oldItem != null && newItem != null) {
                        diffCallback.getChangePayload(oldItem, newItem)
                    } else {
                        throw AssertionError()
                    }
                }
            })
            main.execute {
                if (request == generation) {
                    _list = newList
                    onChanged()
                    result.dispatchUpdatesTo(updateCallback)
                    committed()
                }
            }
        }
    }
}
