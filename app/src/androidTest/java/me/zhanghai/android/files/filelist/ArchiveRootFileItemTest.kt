/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java8.nio.file.Paths
import me.zhanghai.android.files.NoRootAccessRule
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.provider.archive.createArchiveRootPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The stand-in the extract action uses for the folder an archive holds. */
@RunWith(AndroidJUnit4::class)
class ArchiveRootFileItemTest {
    @get:Rule
    val noRootAccess = NoRootAccessRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var archive: File

    @Before
    fun setUp() {
        directory = File(context.cacheDir, "archive-root-${UUID.randomUUID()}")
            .apply { mkdirs() }
        archive = File(directory, "Album.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Notes.txt"))
            zip.write("Nothing to see".toByteArray())
            zip.closeEntry()
        }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun archiveRoot(): FileItem =
        Paths.get(archive.path).loadFileItem().createDummyArchiveRoot()

    @Test
    fun extractingCopiesTheFolderTheArchiveStandsFor() {
        val root = archiveRoot()

        assertEquals(
            Paths.get(archive.path).createArchiveRootPath().toString(),
            root.path.toString()
        )
        assertEquals(MimeType.DIRECTORY, root.mimeType)
        assertTrue(root.attributes.isDirectory)
        assertFalse(root.attributes.isRegularFile)
        assertFalse(root.attributes.isSymbolicLink)
        assertFalse(root.attributes.isOther)
        assertFalse(root.isHidden)
    }

    @Test
    fun theStandInIsOnlyGoodForBeingCopied() {
        // It is only ever put into the selection, so anything a listing would ask of it is a bug
        // rather than something to answer with a made up value.
        val root = archiveRoot()

        assertThrows(UnsupportedOperationException::class.java) { root.attributes.size() }
        assertThrows(UnsupportedOperationException::class.java) {
            root.attributes.lastModifiedTime()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            root.attributes.lastAccessTime()
        }
        assertThrows(UnsupportedOperationException::class.java) { root.attributes.creationTime() }
        assertThrows(UnsupportedOperationException::class.java) { root.attributes.fileKey() }
        assertThrows(UnsupportedOperationException::class.java) {
            root.nameCollationKey.toByteArray()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            root.nameCollationKey.compareTo(archiveRoot().nameCollationKey)
        }
    }
}
