/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Parcelable
import java8.nio.file.Path
import me.zhanghai.android.files.provider.archive.archiveFile
import me.zhanghai.android.files.provider.archive.isArchivePath

class TrailData private constructor(
    val trail: List<Path>,
    private val states: MutableList<Parcelable?>,
    val currentIndex: Int
) {
    fun navigateTo(lastState: Parcelable, path: Path): TrailData {
        val newTrail = createTrail(path)
        val newIndex = newTrail.size - 1
        val prefixSize = getCommonPrefixSize(newTrail)
        val newStates = MutableList<Parcelable?>(newTrail.size) { index ->
            if (index < prefixSize) getState(index, lastState) else null
        }
        if (prefixSize == newTrail.size) {
            // Going up keeps the rest of the trail, so that the way back down is still there.
            for (index in newTrail.size..<trail.size) {
                newTrail.add(trail[index])
                newStates.add(getState(index, lastState))
            }
        }
        return TrailData(newTrail, newStates, newIndex)
    }

    private fun getCommonPrefixSize(otherTrail: List<Path>): Int {
        var size = 0
        while (size < otherTrail.size && size < trail.size && otherTrail[size] == trail[size]) {
            ++size
        }
        return size
    }

    private fun getState(index: Int, lastState: Parcelable): Parcelable? =
        if (index != currentIndex) states[index] else lastState

    fun navigateUp(): TrailData? {
        if (currentIndex == 0) {
            return null
        }
        val newIndex = currentIndex - 1
        return TrailData(trail, states, newIndex)
    }

    val pendingState: Parcelable?
        get() = states.set(currentIndex, null)

    val currentPath: Path
        get() = trail[currentIndex]

    companion object {
        /** @param state the state to restore once [path] has loaded, if any */
        fun of(path: Path, state: Parcelable? = null): TrailData {
            val trail: List<Path> = createTrail(path)
            val states = MutableList<Parcelable?>(trail.size) { null }
            val index = trail.size - 1
            states[index] = state
            return TrailData(trail, states, index)
        }

        private fun createTrail(path: Path): MutableList<Path> {
            var path = path
            val trail = mutableListOf<Path>()
            val archiveFile = if (path.isArchivePath) path.archiveFile else null
            while (true) {
                trail.add(path)
                path = path.parent ?: break
            }
            trail.reverse()
            if (archiveFile != null) {
                val archiveFileParent = archiveFile.parent
                if (archiveFileParent != null) {
                    trail.addAll(0, createTrail(archiveFileParent))
                }
            }
            return trail
        }
    }
}
