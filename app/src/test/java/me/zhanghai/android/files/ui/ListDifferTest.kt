package me.zhanghai.android.files.ui

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Test

class ListDifferTest {
    @Test fun diffRunsOffCallerAndOldResultsCannotReplaceNewerLists() {
        val work = ArrayDeque<Runnable>()
        val commits = ArrayDeque<Runnable>()
        var comparisons = 0
        val differ = ListDiffer(
            object : ListUpdateCallback {
                override fun onInserted(position: Int, count: Int) {}
                override fun onRemoved(position: Int, count: Int) {}
                override fun onMoved(fromPosition: Int, toPosition: Int) {}
                override fun onChanged(position: Int, count: Int, payload: Any?) {}
            },
            object : DiffUtil.ItemCallback<Int>() {
                override fun areItemsTheSame(old: Int, new: Int): Boolean {
                    ++comparisons
                    return old == new
                }
                override fun areContentsTheSame(old: Int, new: Int) = old == new
            },
            worker = Executor { work.add(it) },
            main = Executor { commits.add(it) }
        )
        differ.submit(listOf(1, 2, 3))
        differ.submit(listOf(3, 2, 1))
        differ.submit(listOf(4, 5, 6))
        assertEquals(0, comparisons)
        work.removeFirst().run()
        work.removeFirst().run()
        commits.removeLast().run()
        commits.removeFirst().run()
        assertEquals(listOf(4, 5, 6), differ.list)
    }
}
