/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * What went wrong while nobody was watching logcat, kept in the app's own files so that it can be
 * read later (`run-as me.zhanghai.android.files cat files/diagnostics/diagnostics.log`). Holds the
 * warnings [logWarning] records, crashes, the reasons earlier processes ended and anything that
 * stayed stuck; two files of [DiagnosticLogFile.maxBytes] at most.
 */
object DiagnosticLog {
    private const val MAX_FILE_BYTES = 512L * 1024

    @Volatile
    private var file: DiagnosticLogFile? = null

    private val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "DiagnosticLog").apply { isDaemon = true } }
    }

    val directory: File?
        get() = file?.directory

    fun initialize(directory: File) {
        file = DiagnosticLogFile(directory, MAX_FILE_BYTES)
    }

    /** Appends on a worker, so that a warning on the main thread never waits for the disk. */
    fun append(level: Char, tag: String, message: String, throwable: Throwable? = null) {
        val file = file ?: return
        val entry =
            formatDiagnosticEntry(System.currentTimeMillis(), level, tag, message, throwable)
        executor.execute { file.appendSafe(entry) }
    }

    /** Appends before returning, for a process that is about to die. */
    fun appendNow(level: Char, tag: String, message: String, throwable: Throwable? = null) {
        val file = file ?: return
        file.appendSafe(
            formatDiagnosticEntry(System.currentTimeMillis(), level, tag, message, throwable)
        )
    }

    private fun DiagnosticLogFile.appendSafe(entry: String) {
        try {
            append(entry)
        } catch (_: IOException) {
            // Nowhere left to report it; the entry is also in logcat.
        }
    }
}

/** One log file that is moved aside once it is full, so that the newest entries are never lost. */
class DiagnosticLogFile(val directory: File, val maxBytes: Long) {
    val file = File(directory, "diagnostics.log")

    val previousFile = File(directory, "diagnostics.1.log")

    @Synchronized
    @Throws(IOException::class)
    fun append(entry: String) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create $directory")
        }
        if (file.length() + entry.length > maxBytes && file.exists()) {
            previousFile.delete()
            if (!file.renameTo(previousFile)) {
                throw IOException("Cannot move $file aside")
            }
        }
        file.appendText(entry)
    }
}

private const val MAX_STACK_TRACE_CHARS = 8 * 1024

private val timestampFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

/** A logcat-like entry: a line with the time, level and tag, then the stack trace if any. */
internal fun formatDiagnosticEntry(
    timeMillis: Long,
    level: Char,
    tag: String,
    message: String,
    throwable: Throwable?
): String = buildString {
    append(timestampFormatter.format(Instant.ofEpochMilli(timeMillis)))
    append(' ').append(level).append('/').append(tag).append(": ").append(message).append('\n')
    if (throwable != null) {
        val stackTrace = throwable.stackTraceToString()
        if (stackTrace.length > MAX_STACK_TRACE_CHARS) {
            append(stackTrace, 0, MAX_STACK_TRACE_CHARS).append("\n\t... truncated\n")
        } else {
            append(stackTrace)
            if (!stackTrace.endsWith('\n')) {
                append('\n')
            }
        }
    }
}
