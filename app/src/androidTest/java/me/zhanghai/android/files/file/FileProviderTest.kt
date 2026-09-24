/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.file

import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java8.nio.file.Paths
import me.zhanghai.android.files.NoRootAccessRule
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What another app sees of a file this one hands it as a content URI. */
@RunWith(AndroidJUnit4::class)
class FileProviderTest {
    @get:Rule
    val noRootAccess = NoRootAccessRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var file: File
    private val contents = "The quick brown fox jumps over the lazy dog".repeat(500).toByteArray()

    @Before
    fun setUp() {
        directory = File(context.filesDir, "file-provider-${UUID.randomUUID()}")
            .apply { mkdirs() }
        file = File(directory, "Shared photo.jpg").apply { writeBytes(contents) }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private val uri: Uri
        get() = Paths.get(file.path).fileProviderUri

    private fun query(uri: Uri, vararg projection: String): Cursor =
        checkNotNull(context.contentResolver.query(uri, projection, null, null, null))

    @Test
    fun aSharedFileIsDescribedByItsNameSizeAndType() {
        query(
            uri,
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("Shared photo.jpg", cursor.getString(0))
            assertEquals(contents.size.toLong(), cursor.getLong(1))
            assertEquals("image/jpeg", cursor.getString(2))
            assertEquals(file.lastModified(), cursor.getLong(3))
        }
        assertEquals("image/jpeg", context.contentResolver.getType(uri))
    }

    @Test
    fun anAppThatAsksForEverythingAlsoGetsThePath() {
        checkNotNull(context.contentResolver.query(uri, null, null, null, null)).use { cursor ->
            cursor.moveToFirst()
            val columns = cursor.columnNames.toList()
            assertEquals("Shared photo.jpg", cursor.getString(columns.indexOf("_display_name")))
            assertEquals(file.path, cursor.getString(columns.indexOf("_data")))
        }
    }

    @Test
    fun theListingAnswersForTheFileItWasMadeFrom() {
        // The size and time a directory listing already read are answered without asking the file
        // system again, which for a file on a server would be a round trip on a binder thread.
        val fileItem = Paths.get(file.path).loadFileItem()
        val uri = fileItem.fileProviderUri
        file.appendBytes(contents)

        query(uri, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            .use { cursor ->
                cursor.moveToFirst()
                assertEquals(contents.size.toLong(), cursor.getLong(0))
                assertEquals(
                    fileItem.attributes.lastModifiedTime().toMillis(),
                    cursor.getLong(1)
                )
            }
    }

    @Test
    fun aSharedFileCanBeReadThrough() {
        val read = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

        assertArrayEquals(contents, read)
    }

    @Test
    fun twoReadersEachGetTheirOwnContents() {
        val other = File(directory, "Other.txt").apply { writeText("Other contents") }
        val otherUri = Paths.get(other.path).fileProviderUri

        context.contentResolver.openInputStream(uri)!!.use { first ->
            context.contentResolver.openInputStream(otherUri)!!.use { second ->
                assertEquals("Other contents", second.readBytes().decodeToString())
            }
            assertArrayEquals(contents, first.readBytes())
        }
    }

    @Test
    fun aFileThatIsGoneCannotBeOpened() {
        val goneUri = Paths.get(File(directory, "Gone.txt").path).fileProviderUri

        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openInputStream(goneUri)
        }
    }

    @Test
    fun theFileListIsNotAWritableProvider() {
        assertThrows(UnsupportedOperationException::class.java) {
            context.contentResolver.insert(uri, null)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            context.contentResolver.update(uri, null, null, null)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            context.contentResolver.delete(uri, null, null)
        }
    }
}
