package me.zhanghai.android.files.util

import android.os.CancellationSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

// @see androidx.room.CoroutinesRoom.execute
// The block runs as a child of the caller rather than in GlobalScope: cancelling the caller
// cancels the signal and then waits for the block to notice it, so nothing outlives the call, and
// an exception from the block reaches the caller instead of the global exception handler.
suspend fun <T> runWithCancellationSignal(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: (CancellationSignal) -> T
): T = coroutineScope {
    val signal = CancellationSignal()
    val result = async(dispatcher) { block(signal) }
    try {
        result.await()
    } catch (e: CancellationException) {
        signal.cancel()
        throw e
    }
}
