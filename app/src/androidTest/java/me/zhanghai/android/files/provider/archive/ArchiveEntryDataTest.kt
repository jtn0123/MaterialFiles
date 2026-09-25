/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.provider.NeverUseRootRule
import me.zhanghai.android.files.provider.common.newInputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The data of a large compressed entry, which libarchive hands out in many pieces, read by
 * readers that fill an array bit by bit rather than from its start each time.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveEntryDataTest {
    @get:Rule
    val neverUseRootRule = NeverUseRootRule()

    private lateinit var directory: File
    private lateinit var root: Path

    // Compressible, so that it is deflated, but not a repetition of one block.
    private val content = ByteArray(1024 * 1024) { (it * 31 / 7 + it / 4096).toByte() }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.filesDir, "ArchiveEntryDataTest").apply {
            deleteRecursively()
            mkdirs()
        }
        val zipFile = File(directory, "archive.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("large.bin"))
            zip.write(content)
            zip.closeEntry()
        }
        root = Paths.get(zipFile.path).createArchiveRootPath()
    }

    @After
    fun tearDown() {
        root.fileSystem.close()
        directory.deleteRecursively()
    }

    @Test
    fun readAllBytesReturnsTheEntryUnchanged() {
        val read = root.resolve("large.bin").newInputStream().use { it.readAllBytes() }

        assertArrayEquals(content, read)
    }

    @Test
    fun readsAtAnOffsetLandWhereTheyWereAskedTo() {
        val read = ByteArray(content.size + 16) { -1 }
        root.resolve("large.bin").newInputStream().use { stream ->
            var offset = 16
            while (true) {
                val count = stream.read(read, offset, minOf(1000, read.size - offset))
                if (count <= 0) {
                    break
                }
                offset += count
            }
            assertEquals(read.size, offset)
            assertEquals(-1, stream.read())
        }

        assertArrayEquals(ByteArray(16) { -1 }, read.copyOfRange(0, 16))
        assertArrayEquals(content, read.copyOfRange(16, read.size))
    }
}
