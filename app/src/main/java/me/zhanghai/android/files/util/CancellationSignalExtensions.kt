package me.zhanghai.android.files.util

import android.os.CancellationSignal
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

// @see androidx.room.CoroutinesRoom.execute
suspend fun <T> runWithCancellationSignal(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: (CancellationSignal) -> T
): T {
    val signal = CancellationSignal()
    return suspendCancellableCoroutine { continuation ->
        @OptIn(DelicateCoroutinesApi::class)
        val job = GlobalScope.launch(dispatcher) {
            continuation.resume(block(signal))
        }
        continuation.invokeOnCancellation {
            signal.cancel()
            job.cancel()
        }
    }
}
