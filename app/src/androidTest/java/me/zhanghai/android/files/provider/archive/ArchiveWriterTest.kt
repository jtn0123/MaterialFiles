/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import kotlin.random.Random
import me.zhanghai.android.files.provider.NeverUseRootRule
import me.zhanghai.android.files.provider.archive.archiver.ArchiveWriter
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.createDirectory
import me.zhanghai.android.files.provider.common.createSymbolicLink
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.readAllBytes
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.readSymbolicLink
import me.zhanghai.android.files.provider.common.setMode
import me.zhanghai.android.libarchive.Archive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes archives with libarchive the way the compress file job does, then reads them back
 * through the archive provider. Both sides are native code, so this only runs on a device.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveWriterTest {
    @get:Rule
    val neverUseRootRule = NeverUseRootRule()

    private lateinit var directory: File
    private lateinit var root: Path
    private val fileSystems = mutableListOf<Path>()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.filesDir, "ArchiveWriterTest").apply {
            deleteRecursively()
            mkdirs()
        }
        root = Paths.get(directory.path)
    }

    @After
    fun tearDown() {
        fileSystems.forEach { it.fileSystem.close() }
        directory.deleteRecursively()
    }

    private fun openArchive(archiveFile: Path): Path =
        archiveFile.createArchiveRootPath().also { fileSystems.add(it) }

    /** Writes [names] (relative to [root]) into a new archive and returns its root path. */
    private fun writeArchive(
        archiveName: String,
        names: List<String>,
        format: Int = Archive.FORMAT_ZIP,
        filter: Int = Archive.FILTER_NONE,
        password: String? = null
    ): Path {
        val archiveFile = root.resolve(archiveName)
        archiveFile.newByteChannel(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        ).use { channel ->
            ArchiveWriter(channel, format, filter, password).use { writer ->
                for (name in names) {
                    writer.write(root.resolve(name), Paths.get(name), 0, null)
                }
            }
        }
        return openArchive(archiveFile)
    }

    @Test
    fun writesAndReadsBackAFileADirectoryAndALink() {
        val bytes = Random(7).nextBytes(20_000)
        File(directory, "file.bin").writeBytes(bytes)
        root.resolve("file.bin").setMode(
            setOf(PosixFileModeBit.OWNER_READ, PosixFileModeBit.OWNER_WRITE)
        )
        root.resolve("sub").createDirectory()
        File(directory, "sub/inner.txt").writeText("inner")
        root.resolve("link").createSymbolicLink(Paths.get("file.bin"))

        val archiveRoot = writeArchive(
            "archive.zip",
            listOf("file.bin", "sub", "sub/inner.txt", "link")
        )

        assertArrayEquals(bytes, archiveRoot.resolve("file.bin").readAllBytes())
        assertArrayEquals(
            "inner".toByteArray(),
            archiveRoot.resolve("sub/inner.txt").readAllBytes()
        )
        assertTrue(
            archiveRoot.resolve("sub").readAttributes(BasicFileAttributes::class.java).isDirectory
        )
        assertEquals("file.bin", archiveRoot.resolve("link").readSymbolicLink().toString())
        assertEquals(
            setOf(PosixFileModeBit.OWNER_READ, PosixFileModeBit.OWNER_WRITE),
            archiveRoot.resolve("file.bin").readAttributes(PosixFileAttributes::class.java).mode()
        )
    }

    @Test
    fun keepsTheModificationTimeAndTheSize() {
        File(directory, "file.txt").writeText("content")
        val past = (System.currentTimeMillis() - 100_000) / 1000 * 1000
        File(directory, "file.txt").setLastModified(past)

        val archiveRoot = writeArchive("archive.zip", listOf("file.txt"))

        val attributes = archiveRoot.resolve("file.txt")
            .readAttributes(BasicFileAttributes::class.java)
        assertEquals(7L, attributes.size())
        assertEquals(past, attributes.lastModifiedTime().toMillis())
    }

    @Test
    fun writesATarCompressedWithXz() {
        File(directory, "file.txt").writeText("content of a tar entry")

        val archiveRoot = writeArchive(
            "archive.tar.xz",
            listOf("file.txt"),
            Archive.FORMAT_TAR,
            Archive.FILTER_XZ
        )

        assertArrayEquals(
            "content of a tar entry".toByteArray(),
            archiveRoot.resolve("file.txt").readAllBytes()
        )
    }

    @Test
    fun writesA7z() {
        File(directory, "file.txt").writeText("content of a 7z entry")

        val archiveRoot = writeArchive(
            "archive.7z",
            listOf("file.txt"),
            Archive.FORMAT_7ZIP,
            Archive.FILTER_NONE
        )

        assertArrayEquals(
            "content of a 7z entry".toByteArray(),
            archiveRoot.resolve("file.txt").readAllBytes()
        )
    }

    @Test
    fun anEncryptedZipNeedsItsPasswordToBeRead() {
        File(directory, "file.txt").writeText("secret content")

        val archiveRoot = writeArchive(
            "archive.zip",
            listOf("file.txt"),
            password = "hunter2"
        )

        try {
            archiveRoot.resolve("file.txt").readAllBytes()
            throw AssertionError("expected ArchivePasswordRequiredException")
        } catch (e: ArchivePasswordRequiredException) {
            // Expected.
        }
        archiveRoot.archiveAddPassword("hunter2")
        archiveRoot.archiveRefresh()
        assertArrayEquals(
            "secret content".toByteArray(),
            archiveRoot.resolve("file.txt").readAllBytes()
        )
    }

    @Test
    fun reportsTheBytesItWrote() {
        val bytes = Random(8).nextBytes(60_000)
        File(directory, "file.bin").writeBytes(bytes)
        val archiveFile = root.resolve("archive.zip")
        var reported = 0L

        archiveFile.newByteChannel(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        ).use { channel ->
            ArchiveWriter(channel, Archive.FORMAT_ZIP, Archive.FILTER_NONE, null).use { writer ->
                writer.write(root.resolve("file.bin"), Paths.get("file.bin"), 0) { reported += it }
            }
        }

        assertEquals(bytes.size.toLong(), reported)
        assertArrayEquals(bytes, openArchive(archiveFile).resolve("file.bin").readAllBytes())
    }
}
