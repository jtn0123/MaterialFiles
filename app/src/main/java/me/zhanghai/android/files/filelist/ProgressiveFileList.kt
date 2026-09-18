package me.zhanghai.android.files.filelist

import java.io.IOException
import java.io.InterruptedIOException
import java8.nio.file.DirectoryIteratorException

/** Collects immutable snapshots and remembers missing entries instead of reporting full success. */
internal class ProgressiveFileList<T, R>(
    private val read: (T) -> R,
    private val publish: (List<R>) -> Unit,
    private val now: () -> Long = System::nanoTime
) {
    private val items = mutableListOf<R>()
    private var lastPublish = 0L
    private var hasPublished = false
    private var failures = 0
    private var firstFailure: Exception? = null

    val snapshot: List<R> get() = items.toList()
    val problem: IOException?
        get() = if (failures == 0) null else PartialFileListException(failures, firstFailure)

    fun add(entries: Iterable<T>) {
        val iterator = entries.iterator()
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val entry = try {
                if (!iterator.hasNext()) break
                iterator.next()
            } catch (e: DirectoryIteratorException) {
                if (e.cause is InterruptedIOException) throw e.cause!!
                recordFailure(e)
                break
            }
            try {
                items += read(entry)
            } catch (e: InterruptedIOException) {
                throw e
            } catch (e: IOException) {
                recordFailure(e)
            }
            val current = now()
            if ((!hasPublished && items.isNotEmpty()) || current - lastPublish >= 500_000_000L) {
                publish(snapshot)
                hasPublished = true
                lastPublish = current
            }
        }
    }

    private fun recordFailure(e: Exception) {
        ++failures
        if (firstFailure == null) firstFailure = e
    }
}

class PartialFileListException(val missingCount: Int, cause: Exception?) :
    IOException("Some files could not be read ($missingCount). Refresh to try again.", cause)
