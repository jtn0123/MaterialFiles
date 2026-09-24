/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.ByteStringBuilder
import me.zhanghai.android.files.provider.common.toByteString

/** Where the " (n)" of a duplicate's name is, as `[countStart, countEnd)`, and its n. */
internal class DuplicateCountInfo(val countStart: Int, val countEnd: Int, val count: Int)

/**
 * Finds the duplicate count `/(?<=.) \(\d+\)$/` that ends at [countEnd] in [fileName], or an empty
 * one at [countEnd] with a count of 0 if there is none.
 */
internal fun getDuplicateCountInfo(fileName: ByteString, countEnd: Int): DuplicateCountInfo =
    findDuplicateCount(fileName, countEnd) ?: DuplicateCountInfo(countEnd, countEnd, 0)

private fun findDuplicateCount(fileName: ByteString, countEnd: Int): DuplicateCountInfo? {
    // \)
    val closingIndex = countEnd - 1
    if (!fileName.hasByteAt(closingIndex, ')')) {
        return null
    }
    // \d+
    var digitsStart = closingIndex
    while (digitsStart > 0 && fileName[digitsStart - 1].isAsciiDigit()) {
        --digitsStart
    }
    val count = fileName.substring(digitsStart, closingIndex).toString().toIntOrNull()
        ?: return null
    // \(
    val openingIndex = digitsStart - 1
    if (!fileName.hasByteAt(openingIndex, '(')) {
        return null
    }
    // " "
    val spaceIndex = openingIndex - 1
    // (?<=.)
    if (!fileName.hasByteAt(spaceIndex, ' ') || spaceIndex == 0) {
        return null
    }
    return DuplicateCountInfo(spaceIndex, countEnd, count)
}

private fun ByteString.hasByteAt(index: Int, char: Char): Boolean =
    index >= 0 && this[index] == char.code.toByte()

private fun Byte.isAsciiDigit(): Boolean = this in '0'.code.toByte()..'9'.code.toByte()

/** Replaces the duplicate count described by [countInfo] in [fileName] with " ([count])". */
internal fun setDuplicateCount(
    fileName: ByteString,
    countInfo: DuplicateCountInfo,
    count: Int
): ByteString = ByteStringBuilder(fileName.substring(0, countInfo.countStart))
    .append(" ($count)".toByteString())
    .append(fileName.substring(countInfo.countEnd))
    .toByteString()
