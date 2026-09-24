/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Creates, without starting it, a coroutine for [work] whose [complete] runs exactly once however
 * it ends: also for one that is cancelled before it ever starts, which a `finally` inside the
 * coroutine would miss, and for a cancelled one only after [work] has really stopped running.
 */
internal fun CoroutineScope.launchCompleting(work: suspend () -> Unit, complete: () -> Unit): Job =
    launch(start = CoroutineStart.LAZY) { work() }.apply { invokeOnCompletion { complete() } }
