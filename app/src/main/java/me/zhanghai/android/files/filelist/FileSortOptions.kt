/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.compat.reversedCompat
import me.zhanghai.android.files.file.FileItem

@Parcelize
data class FileSortOptions(val by: By, val order: Order, val isDirectoriesFirst: Boolean) :
    Parcelable {
    fun createComparator(): Comparator<FileItem> {
        val nameComparator = compareBy<FileItem> {
            NAME_UNIMPORTANT_PREFIXES.any { prefix -> it.name.startsWith(prefix) }
        }.thenBy { it.nameCollationKey }
        var comparator = when (by) {
            By.NAME -> nameComparator

            By.TYPE ->
                compareBy<FileItem, String>(String.CASE_INSENSITIVE_ORDER) { it.extension }
                    .then(nameComparator)

            By.SIZE -> compareBy<FileItem> { it.attributes.size() }.then(nameComparator)

            By.LAST_MODIFIED ->
                compareBy<FileItem> { it.attributes.lastModifiedTime() }.then(nameComparator)
        }
        if (order == Order.DESCENDING) {
            comparator = comparator.reversedCompat()
        }
        if (isDirectoriesFirst) {
            val isDirectoryComparator = compareBy<FileItem> { it.attributes.isDirectory }
                .reversedCompat()
            comparator = isDirectoryComparator.then(comparator)
        }
        return comparator
    }

    companion object {
        // Same behavior as Nautilus.
        private val NAME_UNIMPORTANT_PREFIXES = listOf(".", "#")
    }

    enum class By {
        NAME,
        TYPE,
        SIZE,
        LAST_MODIFIED
    }

    enum class Order {
        ASCENDING,
        DESCENDING
    }
}
