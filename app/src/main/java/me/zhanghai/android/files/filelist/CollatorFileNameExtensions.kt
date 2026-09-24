/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Parcelable
import java.text.CollationKey
import java.text.Collator
import kotlin.math.min
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.provider.common.ByteStringBuilder
import me.zhanghai.android.files.provider.common.toByteString

private val COLLATION_SENTINEL = byteArrayOf(1, 1, 1)

// @see https://github.com/GNOME/glib/blob/mainline/glib/gunicollate.c
//      g_utf8_collate_key_for_filename()
fun Collator.getCollationKeyForFileName(source: String): CollationKey =
    FileNameCollationKeyBuilder(this, source).build()

private class FileNameCollationKeyBuilder(
    private val collator: Collator,
    private val source: String
) {
    private val result = ByteStringBuilder()
    private val suffix = ByteStringBuilder()
    private var previousIndex = 0

    fun build(): CollationKey {
        var index = 0
        while (index < source.length) {
            index = when {
                source[index] == '.' -> appendDot(index)
                source[index].isAsciiDigit() -> appendNumber(index)
                else -> index + 1
            }
        }
        appendCollationKeyUntil(source.length)
        result.append(suffix.toByteString())
        return ByteArrayCollationKey(source, result.toByteString().borrowBytes())
    }

    private fun appendCollationKeyUntil(index: Int) {
        if (previousIndex != index) {
            val collationKey = collator.getCollationKey(source.substring(previousIndex, index))
            result.append(collationKey.toByteArray())
        }
    }

    /** Returns the index after the dot at [index]. */
    private fun appendDot(index: Int): Int {
        appendCollationKeyUntil(index)
        result.append(COLLATION_SENTINEL).append(1)
        previousIndex = index + 1
        return previousIndex
    }

    /**
     * Appends the number starting at [start] so that numbers sort by value, with one ':' per digit
     * after the first and its leading zeros left to the suffix, and returns the index after it.
     */
    private fun appendNumber(start: Int): Int {
        appendCollationKeyUntil(start)
        result.append(COLLATION_SENTINEL).append(2)
        previousIndex = start
        var leadingZeros = if (source[start] == '0') 1 else 0
        var digits = 1 - leadingZeros
        var index = start
        while (++index < source.length) {
            val char = source[index]
            if (char == '0' && digits == 0) {
                ++leadingZeros
            } else if (char.isAsciiDigit()) {
                ++digits
            } else {
                if (digits == 0) {
                    ++digits
                    --leadingZeros
                }
                break
            }
        }
        repeat(digits - 1) { result.append(':'.code.toByte()) }
        if (leadingZeros > 0) {
            suffix.append(leadingZeros.toByte())
            previousIndex += leadingZeros
        }
        result.append(source.substring(previousIndex, index).toByteString())
        previousIndex = index
        return index
    }
}

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

@Parcelize
private class ByteArrayCollationKey(
    @Suppress("CanBeParameter")
    private val source: String,
    private val bytes: ByteArray
) : CollationKey(source),
    Parcelable {
    override fun compareTo(other: CollationKey): Int {
        other as ByteArrayCollationKey
        return bytes.unsignedCompareTo(other.bytes)
    }

    override fun toByteArray(): ByteArray = bytes.copyOf()
}

private fun ByteArray.unsignedCompareTo(other: ByteArray): Int {
    val size = size
    val otherSize = other.size
    for (index in 0..<min(size, otherSize)) {
        val byte = this[index].toInt() and 0xFF
        val otherByte = other[index].toInt() and 0xFF
        if (byte < otherByte) {
            return -1
        } else if (byte > otherByte) {
            return 1
        }
    }
    return size - otherSize
}
