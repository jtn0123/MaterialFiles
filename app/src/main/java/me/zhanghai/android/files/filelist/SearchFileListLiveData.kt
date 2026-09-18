/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java.util.concurrent.Future
import java8.nio.file.Path
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.provider.common.search
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.backgroundExecutor

class SearchFileListLiveData(private val path: Path, private val query: String) :
    CloseableLiveData<Stateful<List<FileItem>>>() {
    private var future: Future<Unit>? = null
    private var generation = 0

    init {
        loadValue()
    }

    fun loadValue() {
        future?.cancel(true)
        val request = ++generation
        value = Loading(emptyList())
        fun publish(state: Stateful<List<FileItem>>) {
            me.zhanghai.android.files.app.mainExecutor.execute {
                if (request == generation) value = state
            }
        }
        future = backgroundExecutor.submit<Unit> {
            val result =
                ProgressiveFileList<Path, FileItem>({ it.loadFileItem() }, { publish(Loading(it)) })
            try {
                path.search(query, INTERVAL_MILLIS) { paths: List<Path> -> result.add(paths) }
                val error = result.problem
                publish(
                    if (error ==
                        null
                    ) {
                        Success(result.snapshot)
                    } else {
                        Failure(result.snapshot, error)
                    }
                )
            } catch (e: Exception) {
                publish(Failure(result.snapshot, e))
            }
        }
    }

    override fun close() {
        ++generation
        future?.cancel(true)
    }

    companion object {
        private const val INTERVAL_MILLIS = 500L
    }
}
