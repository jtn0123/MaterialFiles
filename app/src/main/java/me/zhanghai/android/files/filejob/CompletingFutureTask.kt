package me.zhanghai.android.files.filejob

import java.util.concurrent.Callable
import java.util.concurrent.FutureTask

/** FutureTask invokes done exactly once, even when canceled before its worker starts. */
internal class CompletingFutureTask(work: () -> Unit, private val complete: () -> Unit) :
    FutureTask<Unit>(Callable { work() }) {
    override fun done() = complete()
}
