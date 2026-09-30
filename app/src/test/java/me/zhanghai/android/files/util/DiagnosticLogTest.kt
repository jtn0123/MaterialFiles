/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import java.io.File
import java.io.IOException
import java.io.PrintWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun anEntryIsALineWithTheLevelAndTagThenItsStackTrace() {
        val entry = formatDiagnosticEntry(0, 'W', "Tag", "Read /a", IOException("Broken pipe"))
        val lines = entry.lines()
        assertTrue(lines[0], lines[0].endsWith(" W/Tag: Read /a"))
        assertEquals("java.io.IOException: Broken pipe", lines[1])
        assertTrue(lines[2], lines[2].trimStart().startsWith("at "))
        assertTrue(entry.endsWith("\n"))
    }

    @Test
    fun anEntryWithoutAThrowableIsOneLine() {
        val entry = formatDiagnosticEntry(0, 'I', "Tag", "Process started", null)
        assertEquals(1, entry.count { it == '\n' })
        assertTrue(entry.endsWith(" I/Tag: Process started\n"))
    }

    @Test
    fun aHugeStackTraceIsCutShort() {
        val throwable = IOException("Deep").apply {
            stackTrace = Array(2000) { StackTraceElement("Class$it", "method", "File.kt", it) }
        }
        val entry = formatDiagnosticEntry(0, 'W', "Tag", "Read", throwable)
        assertTrue(entry.length < 9 * 1024)
        assertTrue(entry.endsWith("\t... truncated\n"))
    }

    @Test
    fun entriesAreAppendedInOrderAndTheDirectoryIsCreated() {
        val directory = File(temporaryFolder.root, "diagnostics")
        val file = DiagnosticLogFile(directory, 1024)
        file.append("one\n")
        file.append("two\n")
        assertEquals("one\ntwo\n", file.file.readText())
        assertFalse(file.previousFile.exists())
    }

    @Test
    fun aFullFileIsMovedAsideAndTheOlderOneDropped() {
        val file = DiagnosticLogFile(temporaryFolder.root, 10)
        file.append("aaaaaaaa\n")
        file.append("bbbbbbbb\n")
        file.append("cccccccc\n")
        assertEquals("cccccccc\n", file.file.readText())
        assertEquals("bbbbbbbb\n", file.previousFile.readText())
    }

    @Test
    fun aDirectoryThatCannotBeCreatedFailsTheAppend() {
        val blocker = temporaryFolder.newFile("blocker")
        val file = DiagnosticLogFile(File(blocker, "diagnostics"), 1024)
        assertThrows(IOException::class.java) { file.append("entry\n") }
    }

    @Test
    fun anEntryLargerThanAWholeFileIsStillWrittenToAnEmptyLog() {
        val file = DiagnosticLogFile(temporaryFolder.root, 10)
        file.append("a long entry\n")
        assertEquals("a long entry\n", file.file.readText())
        assertFalse(file.previousFile.exists())
    }

    @Test
    fun aFullFileWhoseOlderOneCannotBeDroppedFailsTheAppend() {
        val file = DiagnosticLogFile(temporaryFolder.root, 10)
        file.append("aaaaaaaa\n")
        // A directory with something in it cannot be deleted like a file.
        File(file.previousFile, "child").apply { parentFile!!.mkdirs() }.writeText("child")
        val exception = assertThrows(IOException::class.java) { file.append("bbbbbbbb\n") }
        assertTrue(exception.message!!, exception.message!!.startsWith("Cannot delete"))
        assertEquals("aaaaaaaa\n", file.file.readText())
    }

    @Test
    fun aFullFileThatCannotBeMovedAsideFailsTheAppend() {
        val directory = temporaryFolder.newFolder("read-only")
        val file = DiagnosticLogFile(directory, 10)
        file.append("aaaaaaaa\n")
        assertTrue(directory.setWritable(false))
        try {
            val exception = assertThrows(IOException::class.java) { file.append("bbbbbbbb\n") }
            assertTrue(exception.message!!, exception.message!!.startsWith("Cannot move"))
        } finally {
            directory.setWritable(true)
        }
        assertEquals("aaaaaaaa\n", file.file.readText())
        assertFalse(file.previousFile.exists())
    }

    @Test
    fun aStackTraceWithoutAFinalNewlineStillEndsTheEntry() {
        val throwable = object : Throwable("No newline") {
            override fun printStackTrace(s: PrintWriter) {
                s.print("Throwable: No newline")
            }
        }
        val entry = formatDiagnosticEntry(0, 'W', "Tag", "Read", throwable)
        assertTrue(entry, entry.endsWith(" W/Tag: Read\nThrowable: No newline\n"))
    }

    @Test
    fun appendNowHasWrittenTheEntryWhenItReturns() {
        val directory = File(temporaryFolder.root, "diagnostics")
        DiagnosticLog.initialize(directory)
        try {
            assertEquals(directory, DiagnosticLog.directory)
            DiagnosticLog.appendNow('E', "Tag", "Crashed", IOException("Broken pipe"))
        } finally {
            DiagnosticLog.uninitialize()
        }
        val text = File(directory, "diagnostics.log").readText()
        assertTrue(text, text.contains(" E/Tag: Crashed\njava.io.IOException: Broken pipe\n"))
    }

    @Test
    fun appendNowBeforeTheLogIsInitializedIsIgnored() {
        DiagnosticLog.uninitialize()
        DiagnosticLog.appendNow('E', "Tag", "Crashed")
        assertNull(DiagnosticLog.directory)
    }

    @Test
    fun aLogThatCannotBeWrittenLosesTheEntryRatherThanFailingTheCaller() {
        val blocker = temporaryFolder.newFile("blocker")
        DiagnosticLog.initialize(File(blocker, "diagnostics"))
        try {
            DiagnosticLog.appendNow('E', "Tag", "Crashed")
        } finally {
            DiagnosticLog.uninitialize()
        }
        assertTrue(blocker.isFile)
    }
}
