package me.zhanghai.android.files.viewer.text

import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred

/** Serializes reads, writes and clears without blocking Android lifecycle callbacks. */
internal class TextDraftSession(
    private val store: TextDraftStore,
    private val onFailure: (Exception) -> Unit
) {
    private val loaded = CompletableDeferred<TextDraft?>()

    init {
        executor.execute {
            try {
                loaded.complete(store.read())
            } catch (
                e: Exception
            ) {
                loaded.completeExceptionally(e)
            }
        }
    }

    suspend fun read(): TextDraft? = loaded.await()

    fun write(draft: TextDraft) = enqueue { store.write(draft) }

    fun clear() = enqueue { store.clear() }

    private fun enqueue(action: () -> Unit) {
        executor.execute {
            try {
                action()
            } catch (e: Exception) {
                onFailure(e)
            }
        }
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "text-draft-io").apply { isDaemon = true }
        }
    }
}
