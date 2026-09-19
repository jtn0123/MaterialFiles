/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Parcelable
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.util.ParcelableParceler

/**
 * Where the file list was when it was last left, so that the next launch can start there.
 *
 * @param state the layout manager state (scroll position) of the list at [path], if any
 */
@Parcelize
data class FileListLastLocation(
    val path: @WriteWith<ParcelableParceler> Path,
    val state: Parcelable?
) : Parcelable
