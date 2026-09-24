/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success

/** Which of the file list's state views show for a loading state. */
internal data class FileListStateVisibility(
    val hasFiles: Boolean,
    val isRefreshing: Boolean,
    val isProgressVisible: Boolean,
    val isErrorVisible: Boolean,
    val isEmptyVisible: Boolean
) {
    companion object {
        fun of(stateful: Stateful<out List<*>>, isSearching: Boolean): FileListStateVisibility {
            val hasFiles = !stateful.value.isNullOrEmpty()
            // A search shows its results as they come in, so it never hides them behind the
            // progress.
            val hasContent = hasFiles || isSearching
            val isLoading = stateful is Loading
            return FileListStateVisibility(
                hasFiles = hasFiles,
                isRefreshing = isLoading && hasContent,
                isProgressVisible = isLoading && !hasContent,
                isErrorVisible = stateful is Failure && !hasFiles,
                isEmptyVisible = stateful is Success && !hasFiles
            )
        }
    }
}
