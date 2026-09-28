/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.app.ApplicationExitInfo
import java.io.File
import java.io.IOException
import java.io.InputStream
import me.zhanghai.android.files.util.DiagnosticLog
import me.zhanghai.android.files.util.recordWarnings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What the diagnostics log is told of crashes and of how earlier processes ended. */
class AppDiagnosticsRecordingTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun aCrashIsWrittenDownBeforeTheDefaultHandlerEndsTheProcess() {
        val directory = temporaryFolder.newFolder("diagnostics")
        val handled = mutableListOf<Pair<Thread, Throwable>>()
        var wasWrittenWhenHandled = false
        val recorder = CrashRecorder { thread, throwable ->
            wasWrittenWhenHandled = File(directory, "diagnostics.log").exists()
            handled += thread to throwable
        }
        val thread = Thread("Worker")
        val crash = IllegalStateException("Bad state")
        DiagnosticLog.initialize(directory)
        try {
            recorder.uncaughtException(thread, crash)
        } finally {
            DiagnosticLog.uninitialize()
        }
        assertEquals(listOf(thread to crash), handled)
        assertTrue(wasWrittenWhenHandled)
        val text = File(directory, "diagnostics.log").readText()
        assertTrue(text, text.contains(" E/AppDiagnostics: Crashed on Worker\n"))
        assertTrue(text, text.contains("java.lang.IllegalStateException: Bad state"))
    }

    @Test
    fun aCrashWithoutADefaultHandlerIsStillWrittenDown() {
        val directory = temporaryFolder.newFolder("diagnostics")
        DiagnosticLog.initialize(directory)
        try {
            CrashRecorder(null).uncaughtException(Thread("Worker"), Error("Out of stack"))
        } finally {
            DiagnosticLog.uninitialize()
        }
        assertTrue(File(directory, "diagnostics.log").readText().contains("Crashed on Worker"))
    }

    @Test
    fun earlierExitsAreRecordedOldestFirstAndOnlyOnce() {
        val directory = File(temporaryFolder.root, "diagnostics")
        val exits = listOf(
            ProcessExit(3_000, "me.zhanghai.android.files") { "third" },
            ProcessExit(1_000, "me.zhanghai.android.files") { "first" },
            // The WebView's sandboxed process ends whenever it is done, which is no news.
            ProcessExit(2_000, "me.zhanghai.android.files:sandboxed_process0") { "sandbox" }
        )
        val recorded = mutableListOf<String>()
        recordPreviousExits(directory, { recorded += it }) { exits }
        assertEquals(listOf("first", "third"), recorded)
        assertEquals("3000", File(directory, "last_recorded_exit").readText())

        recorded.clear()
        val newer = ProcessExit(4_000, "me.zhanghai.android.files") { "fourth" }
        recordPreviousExits(directory, { recorded += it }) { exits + newer }
        assertEquals(listOf("fourth"), recorded)
        assertEquals("4000", File(directory, "last_recorded_exit").readText())
    }

    @Test
    fun noNewExitsLeaveTheLastRecordedOneAlone() {
        val directory = temporaryFolder.newFolder("diagnostics")
        File(directory, "last_recorded_exit").writeText("5000\n")
        val recorded = mutableListOf<String>()
        recordPreviousExits(directory, { recorded += it }) {
            listOf(ProcessExit(5_000, "me.zhanghai.android.files") { "old" })
        }
        assertEquals(emptyList<String>(), recorded)
        assertEquals("5000\n", File(directory, "last_recorded_exit").readText())
    }

    @Test
    fun anUnreadableLastRecordedExitMeansEverythingIsRecordedAgain() {
        val directory = temporaryFolder.newFolder("diagnostics")
        File(directory, "last_recorded_exit").writeText("garbage")
        val recorded = mutableListOf<String>()
        recordPreviousExits(directory, { recorded += it }) {
            listOf(ProcessExit(1_000, "me.zhanghai.android.files") { "first" })
        }
        assertEquals(listOf("first"), recorded)
        assertEquals("1000", File(directory, "last_recorded_exit").readText())
    }

    @Test
    fun exitsThatCannotBeRememberedAreLoggedRatherThanCrashingTheApp() {
        // A file where the directory should be: nothing can be written below it.
        val directory = temporaryFolder.newFile("diagnostics")
        val recorded = mutableListOf<String>()
        val warnings = recordWarnings {
            recordPreviousExits(directory, { recorded += it }) {
                listOf(ProcessExit(1_000, "me.zhanghai.android.files") { "first" })
            }
        }
        assertEquals(listOf("first"), recorded)
        val warning = warnings.single()
        assertEquals("AppDiagnostics", warning.tag)
        assertEquals("Record how earlier processes ended", warning.operation)
    }

    @Test
    fun onlyAnAnrOrANativeCrashHasATraceToRead() {
        val trace = { "main thread\n  at Foo.bar\n".byteInputStream() }
        assertEquals("", readExitTrace(ApplicationExitInfo.REASON_LOW_MEMORY, 1, trace))
        assertEquals(
            "\nmain thread\n  at Foo.bar",
            readExitTrace(ApplicationExitInfo.REASON_ANR, 1, trace)
        )
        assertEquals(
            "\nmain thread\n  at Foo.bar",
            readExitTrace(ApplicationExitInfo.REASON_CRASH_NATIVE, 1, trace)
        )
        assertEquals("", readExitTrace(ApplicationExitInfo.REASON_ANR, 1) { null })
    }

    @Test
    fun aLongTraceIsCutToItsFirstLines() {
        val lines = (1..1000).joinToString("\n") { "line $it" }
        val trace = readExitTrace(ApplicationExitInfo.REASON_ANR, 1) { lines.byteInputStream() }
        assertEquals(150, trace.removePrefix("\n").lines().size)
        assertTrue(trace.endsWith("\nline 150"))
    }

    @Test
    fun aTraceThatCannotBeReadIsLoggedAndLeftOut() {
        val failingStream = object : InputStream() {
            override fun read(): Int = throw IOException("Trace gone")
        }
        lateinit var trace: String
        val warnings = recordWarnings {
            trace = readExitTrace(ApplicationExitInfo.REASON_ANR, 15309) { failingStream }
        }
        assertEquals("", trace)
        assertEquals("Read the trace of process 15309", warnings.single().operation)
    }
}
