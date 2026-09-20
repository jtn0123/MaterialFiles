/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Intent
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import java8.nio.file.Paths
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.provider.archive.isArchivePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What the file list is asked to do by the intent that started it. */
@RunWith(AndroidJUnit4::class)
class PickIntentsTest {
    private val documents = Paths.get("/storage/emulated/0/Documents")

    @Test
    fun aPlainViewIntentOnlyShowsAFolder() {
        val intent = Intent(Intent.ACTION_VIEW)

        assertNull(intent.toPickOptions())
        assertEquals(documents, intent.getPathToShow(documents))
        assertNull(intent.getPathToShow(null))
    }

    @Test
    fun anArchiveIsShownAsTheFolderItStandsFor() {
        val zip = Paths.get("/storage/emulated/0/Download/album.zip")
        val intent = Intent(Intent.ACTION_VIEW).setType("application/zip")

        val path = intent.getPathToShow(zip)

        assertTrue(path.toString(), path!!.isArchivePath)
    }

    @Test
    fun aFileThatIsNotAnArchiveIsShownAsItself() {
        val text = Paths.get("/storage/emulated/0/Download/notes.txt")
        val intent = Intent(Intent.ACTION_VIEW).setType("text/plain")

        assertEquals(text, intent.getPathToShow(text))
    }

    @Test
    fun theDownloadsShortcutAlwaysShowsDownloads() {
        val intent = Intent("me.zhanghai.android.files.intent.action.VIEW_DOWNLOADS")

        val expected = Paths.get(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
        )
        assertEquals(expected, intent.getPathToShow(null))
        assertEquals(expected, intent.getPathToShow(documents))
    }

    @Test
    fun openingADocumentPicksOneReadableFileOfTheRequestedTypes() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png"))

        val pickOptions = intent.toPickOptions()!!

        assertEquals(PickOptions.Mode.OPEN_FILE, pickOptions.mode)
        assertFalse(pickOptions.readOnly)
        assertFalse(pickOptions.allowMultiple)
        assertEquals(
            listOf("image/jpeg".asMimeType(), "image/png".asMimeType()),
            pickOptions.mimeTypes
        )
        assertNull(pickOptions.fileName)
    }

    @Test
    fun gettingContentTakesACopyAndSoNeverWrites() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
            .setType("image/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)

        val pickOptions = intent.toPickOptions()!!

        assertEquals(PickOptions.Mode.OPEN_FILE, pickOptions.mode)
        assertTrue(pickOptions.readOnly)
        assertTrue(pickOptions.allowMultiple)
        assertTrue(pickOptions.localOnly)
        assertEquals(listOf("image/*".asMimeType()), pickOptions.mimeTypes)
    }

    @Test
    fun creatingADocumentSuggestsAName() {
        val titled = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TITLE, "Shopping list.txt")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

        val pickOptions = titled.toPickOptions()!!

        assertEquals(PickOptions.Mode.CREATE_FILE, pickOptions.mode)
        assertEquals("Shopping list.txt", pickOptions.fileName)
        // Only one file can be created at a time, whatever the caller asks for.
        assertFalse(pickOptions.allowMultiple)
    }

    @Test
    fun creatingADocumentWithoutATitleFallsBackToTheTypeExtension() {
        val typed = Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain")
        assertEquals("file.txt", typed.toPickOptions()!!.fileName)

        val untyped = Intent(Intent.ACTION_CREATE_DOCUMENT).setType(MimeType.ANY.value)
        assertEquals("file", untyped.toPickOptions()!!.fileName)
    }

    @Test
    fun openingADocumentTreePicksAFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)

        val pickOptions = intent.toPickOptions()!!

        assertEquals(PickOptions.Mode.OPEN_DIRECTORY, pickOptions.mode)
        assertEquals(emptyList<MimeType>(), pickOptions.mimeTypes)
        assertFalse(pickOptions.allowMultiple)
    }

    @Test
    fun aPickIntentShowsWhateverFolderItNames() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")

        assertEquals(documents, intent.getPathToShow(documents))
        assertNull(intent.getPathToShow(null))
    }
}
