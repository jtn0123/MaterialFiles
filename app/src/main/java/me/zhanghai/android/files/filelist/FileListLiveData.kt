/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java.util.concurrent.Future
import java8.nio.file.Path
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.backgroundExecutor

class FileListLiveData(private val path: Path) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private var future: Future<Unit>? = null
    private var generation = 0

    private val observer: PathObserver

    @Volatile
    private var isChangedWhileInactive = false

    init {
        loadValue()
        observer = PathObserver(path) { onChangeObserved() }
    }

    fun loadValue() {
        future?.cancel(true)
        val request = ++generation
        value = Loading(value?.value)
        fun publish(state: Stateful<List<FileItem>>) {
            me.zhanghai.android.files.app.mainExecutor.execute {
                if (request == generation) value = state
            }
        }
        future = backgroundExecutor.submit<Unit> {
            val result =
                ProgressiveFileList<Path, FileItem>({ it.loadFileItem() }, { publish(Loading(it)) })
            try {
                path.newDirectoryStream().use { result.add(it) }
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

    private fun onChangeObserved() {
        if (hasActiveObservers()) {
            loadValue()
        } else {
            isChangedWhileInactive = true
        }
    }

    override fun onActive() {
        if (isChangedWhileInactive) {
            loadValue()
            isChangedWhileInactive = false
        }
    }

    override fun close() {
        ++generation
        observer.close()
        future?.cancel(true)
    }
}
