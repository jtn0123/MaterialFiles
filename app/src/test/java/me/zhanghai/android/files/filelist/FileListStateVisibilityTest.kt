/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java.io.IOException
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Success
import org.junit.Assert.assertEquals
import org.junit.Test

class FileListStateVisibilityTest {
    private val files = listOf("a")

    private fun visibility(
        hasFiles: Boolean = false,
        isRefreshing: Boolean = false,
        isProgressVisible: Boolean = false,
        isErrorVisible: Boolean = false,
        isEmptyVisible: Boolean = false
    ) = FileListStateVisibility(
        hasFiles,
        isRefreshing,
        isProgressVisible,
        isErrorVisible,
        isEmptyVisible
    )

    @Test
    fun aFirstLoadShowsTheProgress() {
        assertEquals(
            visibility(isProgressVisible = true),
            FileListStateVisibility.of(Loading<List<String>>(null), false)
        )
    }

    @Test
    fun reloadingWithFilesOrWhileSearchingRefreshesInstead() {
        assertEquals(
            visibility(hasFiles = true, isRefreshing = true),
            FileListStateVisibility.of(Loading(files), false)
        )
        assertEquals(
            visibility(isRefreshing = true),
            FileListStateVisibility.of(Loading(emptyList<String>()), true)
        )
    }

    @Test
    fun aFailureShowsTheErrorOnlyWithoutFiles() {
        val exception = IOException()
        assertEquals(
            visibility(isErrorVisible = true),
            FileListStateVisibility.of(Failure<List<String>>(null, exception), false)
        )
        assertEquals(
            visibility(hasFiles = true),
            FileListStateVisibility.of(Failure(files, exception), false)
        )
    }

    @Test
    fun anEmptySuccessShowsTheEmptyView() {
        assertEquals(
            visibility(isEmptyVisible = true),
            FileListStateVisibility.of(Success(emptyList<String>()), false)
        )
        assertEquals(
            visibility(hasFiles = true),
            FileListStateVisibility.of(Success(files), true)
        )
    }
}
