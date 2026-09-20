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
import java8.nio.file.AccessDeniedException
import java8.nio.file.AccessMode
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.ProviderMismatchException
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.provider.NeverUseRootRule
import me.zhanghai.android.files.provider.common.ByteStringPath
import me.zhanghai.android.files.provider.common.PosixFileAttributeView
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixGroup
import me.zhanghai.android.files.provider.common.PosixUser
import me.zhanghai.android.files.provider.common.ReadOnlyFileSystemException
import me.zhanghai.android.files.provider.common.checkAccess
import me.zhanghai.android.files.provider.common.getFileAttributeView
import me.zhanghai.android.files.provider.common.getFileStore
import me.zhanghai.android.files.provider.common.isHidden
import me.zhanghai.android.files.provider.common.isSameFile
import me.zhanghai.android.files.provider.common.observe
import me.zhanghai.android.files.provider.common.readAllBytes
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.search
import me.zhanghai.android.files.provider.common.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The archive provider is read only, and every operation that would change an archive has to say
 * so rather than silently doing nothing.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveFileSystemProviderTest {
    @get:Rule
    val neverUseRootRule = NeverUseRootRule()

    private lateinit var directory: File
    private lateinit var zipFile: File
    private lateinit var root: Path

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.filesDir, "ArchiveFileSystemProviderTest").apply {
            deleteRecursively()
            mkdirs()
        }
        zipFile = File(directory, "archive.zip")
        writeZip("file.txt" to "content", "sub/inner.txt" to "inner")
        root = Paths.get(zipFile.path).createArchiveRootPath()
    }

    @After
    fun tearDown() {
        root.fileSystem.close()
        directory.deleteRecursively()
    }

    private fun writeZip(vararg entries: Pair<String, String>) {
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private val provider get() = root.fileSystem.provider()

    private val file get() = root.resolve("file.txt")

    @Test
    fun readsAnEntryAndItsAttributes() {
        assertArrayEquals("content".toByteArray(), file.readAllBytes())

        val attributes = file.readAttributes(BasicFileAttributes::class.java)

        assertEquals(7L, attributes.size())
        assertTrue(attributes.isRegularFile)
        assertTrue(root.resolve("sub").readAttributes(BasicFileAttributes::class.java).isDirectory)
    }

    @Test
    fun aMissingEntryHasNoAttributes() {
        try {
            root.resolve("missing.txt").readAttributes(BasicFileAttributes::class.java)
            fail("expected NoSuchFileException")
        } catch (e: NoSuchFileException) {
            assertEquals(root.resolve("missing.txt").toString(), e.file)
        }
    }

    @Test
    fun everyChangingOperationSaysTheFileSystemIsReadOnly() {
        assertThrowsReadOnly { provider.createDirectory(root.resolve("new")) }
        assertThrowsReadOnly {
            provider.createSymbolicLink(root.resolve("link"), ByteStringPath("x".toByteString()))
        }
        assertThrowsReadOnly { provider.createLink(root.resolve("link"), file) }
        assertThrowsReadOnly { provider.delete(file) }
        assertThrowsReadOnly { provider.copy(file, root.resolve("copy")) }
        assertThrowsReadOnly { provider.move(file, root.resolve("moved")) }
        assertTrue(root.fileSystem.isReadOnly)
    }

    @Test
    fun aLinkTargetOfAnotherProviderIsRefused() {
        try {
            provider.createSymbolicLink(root.resolve("link"), Paths.get("/tmp/x"))
            fail("expected ProviderMismatchException")
        } catch (e: ProviderMismatchException) {
            // Expected.
        }
    }

    @Test
    fun anEntryCannotBeOpenedForWritingOrSeeking() {
        try {
            provider.newInputStream(file, StandardOpenOption.WRITE)
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            // Expected.
        }
        try {
            provider.newByteChannel(file, setOf(StandardOpenOption.READ))
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            // Expected.
        }
        try {
            provider.newFileChannel(file, setOf(StandardOpenOption.READ))
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            // Expected.
        }
    }

    @Test
    fun anEntryIsReadableButNeverWritableOrExecutable() {
        file.checkAccess()
        file.checkAccess(AccessMode.READ)

        try {
            file.checkAccess(AccessMode.WRITE)
            fail("expected AccessDeniedException")
        } catch (e: AccessDeniedException) {
            assertEquals(file.toString(), e.file)
        }
        try {
            file.checkAccess(AccessMode.EXECUTE)
            fail("expected AccessDeniedException")
        } catch (e: AccessDeniedException) {
            assertEquals(file.toString(), e.file)
        }
        try {
            root.resolve("missing.txt").checkAccess()
            fail("expected NoSuchFileException")
        } catch (e: NoSuchFileException) {
            // Expected.
        }
    }

    @Test
    fun onlyThePathItselfIsTheSameFile() {
        assertTrue(file.isSameFile(root.resolve("file.txt")))
        assertFalse(file.isSameFile(root.resolve("sub/inner.txt")))
        assertFalse(provider.isSameFile(file, Paths.get(zipFile.path)))
        assertFalse(file.isHidden)
    }

    @Test
    fun theFileStoreIsNamedAfterTheArchiveFile() {
        val fileStore = file.getFileStore()

        assertEquals(zipFile.path, fileStore.name())
        assertTrue(fileStore.isReadOnly)
    }

    @Test
    fun theAttributeViewRefusesEveryChange() {
        val view = file.getFileAttributeView(PosixFileAttributeView::class.java)!!

        assertThrowsUnsupported { view.setTimes(null, null, null) }
        assertThrowsUnsupported { view.setOwner(PosixUser(0, null)) }
        assertThrowsUnsupported { view.setGroup(PosixGroup(0, null)) }
        assertThrowsUnsupported { view.setMode(setOf(PosixFileModeBit.OWNER_READ)) }
        assertThrowsUnsupported { view.setSeLinuxContext("u:object_r:x:s0".toByteString()) }
        assertThrowsUnsupported { view.restoreSeLinuxContext() }
    }

    @Test
    fun theOperationsAnArchiveCannotSupportAreRefused() {
        assertThrowsUnsupported { provider.readAttributes(file, "basic:*") }
        assertThrowsUnsupported { provider.setAttribute(file, "basic:size", 0L) }
        assertThrowsUnsupported { file.observe(0) }
        assertThrowsUnsupported { root.fileSystem.newWatchService() }
        assertThrowsUnsupported { file.readAttributes(UnsupportedAttributes::class.java) }
        assertNull(provider.getFileAttributeView(file, UnsupportedView::class.java))
        assertThrowsUnsupported { root.toRealPath() }
        assertThrowsUnsupported { root.toFile() }
    }

    @Test
    fun searchFindsEntriesByName() {
        val found = mutableListOf<Path>()

        root.search("inner", 0) { synchronized(found) { found += it } }

        assertEquals(
            listOf(root.resolve("sub").resolve("inner.txt")),
            synchronized(found) { found.toList() }
        )
    }

    @Test
    fun refreshingPicksUpANewEntry() {
        writeZip("file.txt" to "content", "sub/inner.txt" to "inner", "added.txt" to "added")

        root.archiveRefresh()

        assertArrayEquals("added".toByteArray(), root.resolve("added.txt").readAllBytes())
        assertEquals(Paths.get(zipFile.path), root.archiveFile)
    }

    @Test
    fun aFileThatIsNotAnArchiveFailsWhenItIsRead() {
        val notAnArchive = File(directory, "not-an-archive.zip")
        notAnArchive.writeText("this is not a zip file")
        val notAnArchiveRoot = Paths.get(notAnArchive.path).createArchiveRootPath()

        try {
            notAnArchiveRoot.resolve("anything").readAttributes(BasicFileAttributes::class.java)
            fail("expected FileSystemException")
        } catch (e: FileSystemException) {
            // Expected.
        } finally {
            notAnArchiveRoot.fileSystem.close()
        }
    }

    private inline fun assertThrowsReadOnly(block: () -> Unit) {
        try {
            block()
            fail("expected ReadOnlyFileSystemException")
        } catch (e: ReadOnlyFileSystemException) {
            // Expected.
        }
    }

    private inline fun assertThrowsUnsupported(block: () -> Unit) {
        try {
            block()
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            // Expected.
        }
    }

    private interface UnsupportedAttributes : BasicFileAttributes

    private interface UnsupportedView : java8.nio.file.attribute.FileAttributeView
}
