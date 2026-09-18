/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.content.Context
import android.os.Parcelable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.nio.charset.StandardCharsets
import java8.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.provider.common.readAllBytes
import me.zhanghai.android.files.provider.common.size
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.isFinished
import me.zhanghai.android.files.util.isReady
import me.zhanghai.android.files.util.toError
import me.zhanghai.android.files.util.toLoading

class TextEditorViewModel(file: Path) : ViewModel() {
    private val _file = MutableStateFlow(file)
    val file = _file.asStateFlow()

    private val bytesState = MutableStateFlow<DataState<ByteArray>>(DataState.Loading())

    private var loadJob: Job? = null
    private var reloadJob: Job? = null

    init {
        viewModelScope.launch {
            _file.collectLatest {
                loadJob?.cancel()?.also { loadJob = null }
                reloadJob?.cancel()?.also { reloadJob = null }
                loadJob = launch {
                    mapFileToBytesState(it)
                    if (isActive) {
                        loadJob = null
                    }
                }
            }
        }
    }

    fun reload() {
        viewModelScope.launch {
            loadJob?.cancel()?.also { loadJob = null }
            reloadJob?.cancel()?.also { reloadJob = null }
            reloadJob = launch {
                mapFileToBytesState(_file.value)
                if (isActive) {
                    reloadJob = null
                }
            }
        }
    }

    private suspend fun mapFileToBytesState(file: Path) {
        bytesState.value = bytesState.value.toLoading()
        try {
            val bytes = runInterruptible(Dispatchers.IO) {
                val size = file.size()
                if (size > MAX_FILE_SIZE) {
                    throw IOException("File size $size is too large")
                }
                file.readAllBytes()
            }
            currentCoroutineContext().ensureActive()
            bytesState.value = DataState.Success(bytes)
        } catch (e: CancellationException) {
            e.printStackTrace()
        } catch (e: Exception) {
            bytesState.value = bytesState.value.toError(e)
        }
    }

    val encoding = MutableStateFlow(StandardCharsets.UTF_8)

    private val _textState = MutableStateFlow<DataState<String>>(DataState.Loading())
    val textState = _textState.asStateFlow()

    init {
        viewModelScope.launch {
            bytesState.combine(encoding) { bytesState, encoding -> bytesState to encoding }
                .collectLatest { (bytesState, encoding) ->
                    when (bytesState) {
                        is DataState.Loading -> _textState.value = _textState.value.toLoading()

                        is DataState.Success -> {
                            _textState.value = _textState.value.toLoading()
                            try {
                                val text = withContext(Dispatchers.Default) {
                                    String(bytesState.data, encoding)
                                }
                                currentCoroutineContext().ensureActive()
                                _textState.value = DataState.Success(text)
                            } catch (e: CancellationException) {
                                e.printStackTrace()
                            } catch (e: Exception) {
                                _textState.value = _textState.value.toError(e)
                            }
                        }

                        is DataState.Error ->
                            _textState.value = _textState.value.toError(bytesState.throwable)
                    }
                }
        }
    }

    val isTextChanged = MutableStateFlow(false)

    private val _writeFileState =
        MutableStateFlow<ActionState<Pair<Path, String>, Unit>>(ActionState.Ready())
    val writeFileState = _writeFileState.asStateFlow()

    fun writeFile(path: Path, text: String, context: Context) {
        viewModelScope.launch {
            check(_writeFileState.value.isReady)
            val argument = path to text
            _writeFileState.value = ActionState.Running(argument)
            try {
                val bytes = withContext(Dispatchers.Default) {
                    text.toByteArray(encoding.value)
                }
                FileJobService.write(path, bytes, context) { successful ->
                    if (successful) {
                        loadJob?.cancel()?.also { loadJob = null }
                        reloadJob?.cancel()?.also { reloadJob = null }
                        bytesState.value = DataState.Success(bytes)
                    }
                    _writeFileState.value = if (successful) {
                        ActionState.Success(argument, Unit)
                    } else {
                        // The error will be toasted by service so we should never show it in UI, but we
                        // need a non-null value here.
                        ActionState.Error(argument, Throwable())
                    }
                }
            } catch (e: Exception) {
                _writeFileState.value = ActionState.Error(argument, e)
                if (e is CancellationException) throw e
                android.widget.Toast.makeText(
                    context,
                    e.message,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun finishWritingFile() {
        viewModelScope.launch {
            check(_writeFileState.value.isFinished)
            _writeFileState.value = ActionState.Ready()
        }
    }

    private var editTextSavedState: Parcelable? = null

    fun setEditTextSavedState(editTextSavedState: Parcelable?) {
        this.editTextSavedState = editTextSavedState
    }

    fun removeEditTextSavedState(): Parcelable? {
        val savedState = editTextSavedState
        editTextSavedState = null
        return savedState
    }

    companion object {
        private const val MAX_FILE_SIZE = 1024 * 1024.toLong()
    }
}
