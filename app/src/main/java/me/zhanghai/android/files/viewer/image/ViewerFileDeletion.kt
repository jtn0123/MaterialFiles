/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import androidx.annotation.VisibleForTesting
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import java.io.IOException
import java8.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.delete
import me.zhanghai.android.files.util.logWarning
import me.zhanghai.android.files.util.showActionSnackbar
import me.zhanghai.android.files.util.toUserMessage

/**
 * Deleting what a viewer shows. On a network share a delete is a round trip to the server, up to
 * its timeout on a bad connection, so it never runs on the main thread.
 */
object ViewerFileDeletion {
    /** What actually deletes a file; tests swap it for one that is slow or records its thread. */
    @VisibleForTesting
    @Volatile
    var deleter: (Path) -> Unit = { it.delete() }

    /**
     * Deletes [path] on [dispatcher] and returns what went wrong, or `null` when it is gone. A
     * failure is logged with [tag].
     */
    suspend fun delete(
        path: Path,
        tag: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): IOException? = withContext(dispatcher) {
        try {
            deleter(path)
            null
        } catch (e: IOException) {
            e.logWarning(tag, "Delete $path")
            e
        }
    }
}

/**
 * Deletes [path] off the main thread for as long as the view of this fragment lives, then calls
 * [onDeleted] on the main thread, or offers to retry with [retry] in a snackbar when it failed.
 */
fun Fragment.deleteViewedFile(path: Path, tag: String, retry: () -> Unit, onDeleted: () -> Unit) {
    val view = requireView()
    viewLifecycleOwner.lifecycleScope.launch {
        val exception = ViewerFileDeletion.delete(path, tag)
        if (exception != null) {
            view.showActionSnackbar(exception.toUserMessage(requireContext()), R.string.retry) {
                retry()
            }
            return@launch
        }
        onDeleted()
    }
}
