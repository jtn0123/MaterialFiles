/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import java.io.IOException
import java.io.InputStream
import me.zhanghai.android.files.compat.getSystemServiceCompat
import me.zhanghai.android.files.util.DiagnosticLog
import me.zhanghai.android.files.util.StuckOperations
import me.zhanghai.android.files.util.backgroundExecutor
import me.zhanghai.android.files.util.logWarning

private const val TAG = "AppDiagnostics"

/** Lines of an ANR or native crash trace worth keeping: the main thread comes first. */
private const val MAX_TRACE_LINES = 150

private const val MAX_EXIT_INFOS = 16

fun initializeDiagnostics() {
    DiagnosticLog.initialize(application.filesDir.resolve("diagnostics"))
    DiagnosticLog.append('I', TAG, "Process started")
    Thread.setDefaultUncaughtExceptionHandler(
        CrashRecorder(Thread.getDefaultUncaughtExceptionHandler())
    )
    StuckOperations.startChecking()
    ProcessLifecycleOwner.get().lifecycle.addObserver(
        object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                DiagnosticLog.append('I', TAG, "In the foreground")
            }

            // Work still running once the app is cached keeps Android from freezing it, and
            // Android kills an app it cannot freeze.
            override fun onStop(owner: LifecycleOwner) {
                DiagnosticLog.append(
                    'I',
                    TAG,
                    "In the background, ${StuckOperations.instance.runningCount} reads running"
                )
            }
        }
    )
    backgroundExecutor.execute { recordPreviousExits() }
}

/** Keeps a crash in [DiagnosticLog] before handing it on to the handler that ends the process. */
internal class CrashRecorder(private val defaultHandler: Thread.UncaughtExceptionHandler?) :
    Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        DiagnosticLog.appendNow('E', TAG, "Crashed on ${thread.name}", throwable)
        defaultHandler?.uncaughtException(thread, throwable)
    }
}

private fun recordPreviousExits() {
    val directory = DiagnosticLog.directory ?: return
    recordPreviousExits(directory, { DiagnosticLog.append('I', TAG, it) }) {
        application.getSystemServiceCompat(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(application.packageName, 0, MAX_EXIT_INFOS)
            .map { ProcessExit(it.timestamp, it.processName) { it.format() + it.readTrace() } }
    }
}

/** An earlier process of the app, and how to describe the way it ended. */
internal class ProcessExit(val timestamp: Long, val processName: String, val describe: () -> String)

/**
 * Records how earlier processes of the app ended, since Android keeps that (a kill for using too
 * much, an ANR with its trace) but nobody looks unless something already seemed wrong. Each exit
 * is recorded once: the time of the last one recorded is kept in [directory].
 */
internal fun recordPreviousExits(
    directory: File,
    record: (String) -> Unit,
    readExits: () -> List<ProcessExit>
) {
    val lastRecordedFile = File(directory, "last_recorded_exit")
    try {
        val lastRecorded = lastRecordedFile.takeIf { it.exists() }?.readText()?.trim()
            ?.toLongOrNull() ?: 0L
        val exits = readExits()
            // Not the WebView's own sandboxed processes, which end whenever it is done.
            .filter { it.timestamp > lastRecorded && ':' !in it.processName }
            .sortedBy { it.timestamp }
        for (exit in exits) {
            record(exit.describe())
        }
        exits.lastOrNull()?.let {
            directory.mkdirs()
            lastRecordedFile.writeText(it.timestamp.toString())
        }
    } catch (e: IOException) {
        e.logWarning(TAG, "Record how earlier processes ended")
    }
}

private fun ApplicationExitInfo.format(): String = formatProcessExit(
    timestamp,
    processName,
    pid,
    exitReasonName(reason),
    status,
    importance,
    rss,
    description
)

private fun ApplicationExitInfo.readTrace(): String = readExitTrace(reason, pid) {
    traceInputStream
}

/** The start of the trace of an ANR or a native crash, the only exits that have one. */
internal fun readExitTrace(reason: Int, pid: Int, openTrace: () -> InputStream?): String {
    if (reason != ApplicationExitInfo.REASON_ANR &&
        reason != ApplicationExitInfo.REASON_CRASH_NATIVE
    ) {
        return ""
    }
    return try {
        openTrace()?.bufferedReader()?.use { reader ->
            reader.lineSequence().take(MAX_TRACE_LINES).joinToString("\n", prefix = "\n")
        } ?: ""
    } catch (e: IOException) {
        e.logWarning(TAG, "Read the trace of process $pid")
        ""
    }
}

internal fun formatProcessExit(
    timestamp: Long,
    processName: String,
    pid: Int,
    reasonName: String,
    status: Int,
    importance: Int,
    rssKb: Long,
    description: String?
): String = "Earlier process $processName (pid $pid) ended at " +
    "${java.time.Instant.ofEpochMilli(timestamp)}: $reasonName, status $status, " +
    "importance $importance, rss ${rssKb / 1024} MB" +
    (description?.let { ", $it" } ?: "")

internal fun exitReasonName(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
    ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
    ApplicationExitInfo.REASON_SIGNALED -> "signaled"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
    ApplicationExitInfo.REASON_CRASH -> "crash"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
    ApplicationExitInfo.REASON_ANR -> "ANR"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "user requested"
    ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
    ApplicationExitInfo.REASON_OTHER -> "other"
    ApplicationExitInfo.REASON_FREEZER -> "freezer"
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package state change"
    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package updated"
    else -> "reason $reason"
}
