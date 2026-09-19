/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ftpserver

/**
 * The passive-mode data port setting: ports and ranges separated by commas, e.g. `2300-2400` or
 * `2300, 2301`, which is what Apache FtpServer's `passivePorts` accepts.
 */
object FtpPassivePorts {
    private const val MIN_PORT = 1
    private const val MAX_PORT = 65535

    /**
     * Parses [text] and returns it in canonical form (sorted, deduplicated, `a-b` ranges,
     * `, ` separators), an empty string for blank input (any free port), or null when it is not
     * a list of ports and ascending ranges within 1..65535.
     */
    fun normalize(text: String): String? {
        val ranges = mutableListOf<IntRange>()
        for (part in text.split(',')) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) {
                if (text.isBlank()) {
                    continue
                }
                return null
            }
            val bounds = trimmed.split('-')
            val range = when (bounds.size) {
                1 -> bounds[0].toPortOrNull()?.let { it..it }

                2 -> {
                    val start = bounds[0].toPortOrNull() ?: return null
                    val end = bounds[1].toPortOrNull() ?: return null
                    if (start <= end) start..end else null
                }

                else -> null
            } ?: return null
            ranges += range
        }
        return ranges.merged().joinToString(", ") {
            if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}"
        }
    }

    private fun String.toPortOrNull(): Int? =
        trim().takeIf { it.all { c -> c in '0'..'9' } }?.toIntOrNull()?.takeIf {
            it in
                MIN_PORT..MAX_PORT
        }

    private fun List<IntRange>.merged(): List<IntRange> {
        val result = mutableListOf<IntRange>()
        for (range in sortedBy { it.first }) {
            val last = result.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                result[result.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                result += range
            }
        }
        return result
    }
}
