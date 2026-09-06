/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlin.random.Random
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.readAllBytes
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reads entries out of a zip through the archive provider in the orders that exercise the
 * cached, forward-only reader: in archive order (reuse), backwards (reopen), and with two
 * streams open at once (a second reader).
 */
@RunWith(AndroidJUnit4::class)
class ArchiveExtractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val directory =
        File(Environment.getExternalStorageDirectory(), "Download/MaterialFilesArchiveTest")
    private lateinit var zipFile: File
    private lateinit var root: Path
    private val contents = linkedMapOf<String, ByteArray>()

    @Before
    fun setUp() {
        // The archive is read through the Linux provider like any user file.
        val packageName = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.executeShellCommand(
            "appops set $packageName MANAGE_EXTERNAL_STORAGE allow"
        ).close()
        directory.mkdirs()
        zipFile = File(directory, "extraction-test.zip")
        val random = Random(42)
        for (i in 1..6) {
            contents["file$i.bin"] = random.nextBytes(i * 3000)
        }
        contents["nested/inner.txt"] = "inner".toByteArray()
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            for ((name, bytes) in contents) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        root = Paths.get(zipFile.path).createArchiveRootPath()
    }

    @After
    fun tearDown() {
        root.fileSystem.close()
        directory.deleteRecursively()
    }

    @Test
    fun readsEveryEntryInArchiveOrder() {
        for ((name, bytes) in contents) {
            assertArrayEquals(name, bytes, root.resolve(name).readAllBytes())
        }
    }

    @Test
    fun readsEntriesBackwards() {
        for ((name, bytes) in contents.entries.reversed()) {
            assertArrayEquals(name, bytes, root.resolve(name).readAllBytes())
        }
    }

    @Test
    fun readsTheSameEntryTwice() {
        val (name, bytes) = contents.entries.first()
        assertArrayEquals(bytes, root.resolve(name).readAllBytes())
        assertArrayEquals(bytes, root.resolve(name).readAllBytes())
    }

    @Test
    fun readsTwoEntriesWithBothStreamsOpen() {
        val names = contents.keys.toList()
        root.resolve(names[1]).newInputStream().use { first ->
            root.resolve(names[3]).newInputStream().use { second ->
                assertArrayEquals(contents[names[3]], second.readBytes())
            }
            assertArrayEquals(contents[names[1]], first.readBytes())
        }
        // The cached reader is free again and positioned after names[3]; a later entry reuses it.
        assertArrayEquals(contents[names[5]], root.resolve(names[5]).readAllBytes())
    }

    @Test
    fun listsTheRootAndTheNestedDirectory() {
        val rootNames = root.fileSystem.provider().newDirectoryStream(root) { true }.use { stream ->
            stream.map { it.fileName.toString() }.sorted()
        }
        val expected = (1..6).map { "file$it.bin" } + "nested"
        assertEquals(expected, rootNames)
    }
}
