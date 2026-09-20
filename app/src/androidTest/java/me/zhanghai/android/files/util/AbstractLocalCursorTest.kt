/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.database.CharArrayBuffer
import android.database.Cursor
import android.database.CursorIndexOutOfBoundsException
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** The cursor the app hands out for its own content providers, over rows it already holds. */
@RunWith(AndroidJUnit4::class)
class AbstractLocalCursorTest {
    private val cursor: Cursor = TestCursor(
        arrayOf("name", "size", "ratio", "blob", "nothing"),
        listOf(
            arrayOf("one", 1L, 1.5f, byteArrayOf(1, 2), null),
            arrayOf("two", "2", 2.5, byteArrayOf(3), null)
        )
    )

    @Test
    fun movingWalksTheRowsAndStopsAtBothEnds() {
        assertTrue(cursor.isBeforeFirst)
        assertEquals(-1, cursor.position)

        assertTrue(cursor.moveToFirst())
        assertTrue(cursor.isFirst)
        assertTrue(cursor.moveToNext())
        assertTrue(cursor.isLast)
        assertFalse(cursor.moveToNext())
        assertTrue(cursor.isAfterLast)
        assertEquals(2, cursor.position)

        assertTrue(cursor.moveToLast())
        assertTrue(cursor.moveToPrevious())
        assertEquals(0, cursor.position)
        assertFalse(cursor.moveToPrevious())
        assertEquals(-1, cursor.position)
        assertTrue(cursor.move(2))
        assertEquals(1, cursor.position)
    }

    @Test
    fun columnsAreFoundByNameIgnoringCase() {
        assertEquals(5, cursor.columnCount)
        assertEquals(1, cursor.getColumnIndex("SIZE"))
        assertEquals(-1, cursor.getColumnIndex("missing"))
        assertEquals(1, cursor.getColumnIndexOrThrow("size"))
        assertEquals("ratio", cursor.getColumnName(2))
        try {
            cursor.getColumnIndexOrThrow("missing")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Column 'missing' does not exist"))
            assertTrue(e.message!!.contains("nothing"))
        }
    }

    @Test
    fun valuesAreConvertedToWhateverIsAskedFor() {
        cursor.moveToFirst()

        assertEquals("one", cursor.getString(0))
        assertEquals(1, cursor.getShort(1).toInt())
        assertEquals(1, cursor.getInt(1))
        assertEquals(1L, cursor.getLong(1))
        assertEquals(1.5f, cursor.getFloat(2), 0f)
        assertEquals(1.5, cursor.getDouble(2), 0.0)
        assertArrayEquals(byteArrayOf(1, 2), cursor.getBlob(3))

        cursor.moveToNext()

        // A string column still answers every numeric getter, by parsing itself.
        assertEquals(2, cursor.getShort(1).toInt())
        assertEquals(2, cursor.getInt(1))
        assertEquals(2L, cursor.getLong(1))
        assertEquals(2f, cursor.getFloat(1), 0f)
        assertEquals(2.0, cursor.getDouble(1), 0.0)
    }

    @Test
    fun aMissingValueIsZeroOfWhateverIsAskedFor() {
        cursor.moveToFirst()

        assertTrue(cursor.isNull(4))
        assertNull(cursor.getString(4))
        assertNull(cursor.getBlob(4))
        assertEquals(0, cursor.getShort(4).toInt())
        assertEquals(0, cursor.getInt(4))
        assertEquals(0L, cursor.getLong(4))
        assertEquals(0f, cursor.getFloat(4), 0f)
        assertEquals(0.0, cursor.getDouble(4), 0.0)
    }

    @Test
    fun theTypeOfAColumnFollowsWhatItHolds() {
        cursor.moveToFirst()

        assertEquals(Cursor.FIELD_TYPE_STRING, cursor.getType(0))
        assertEquals(Cursor.FIELD_TYPE_INTEGER, cursor.getType(1))
        assertEquals(Cursor.FIELD_TYPE_FLOAT, cursor.getType(2))
        assertEquals(Cursor.FIELD_TYPE_BLOB, cursor.getType(3))
        assertEquals(Cursor.FIELD_TYPE_NULL, cursor.getType(4))
    }

    @Test
    fun readingOutsideTheRowsOrTheColumnsFails() {
        try {
            cursor.getString(0)
            fail("expected CursorIndexOutOfBoundsException")
        } catch (e: CursorIndexOutOfBoundsException) {
            // Expected: the cursor is still before the first row.
        }
        cursor.moveToFirst()
        try {
            cursor.getString(5)
            fail("expected CursorIndexOutOfBoundsException")
        } catch (e: CursorIndexOutOfBoundsException) {
            assertTrue(e.message!!.contains("# of columns: 5"))
        }
    }

    @Test
    fun copyingAStringReusesABufferThatIsBigEnough() {
        cursor.moveToFirst()
        val buffer = CharArrayBuffer(16)

        cursor.copyStringToBuffer(0, buffer)

        assertEquals(3, buffer.sizeCopied)
        assertEquals("one", String(buffer.data, 0, buffer.sizeCopied))
        val data = buffer.data

        cursor.copyStringToBuffer(4, buffer)

        assertEquals(0, buffer.sizeCopied)
        assertTrue("The buffer was replaced", data === buffer.data)
    }

    @Test
    fun copyingAStringGrowsABufferThatIsTooSmall() {
        cursor.moveToFirst()
        val buffer = CharArrayBuffer(1)

        cursor.copyStringToBuffer(0, buffer)

        assertEquals(3, buffer.sizeCopied)
        assertEquals("one", String(buffer.data, 0, buffer.sizeCopied))
    }

    @Test
    fun closingIsRememberedAndRequeryingChangesNothing() {
        assertFalse(cursor.isClosed)

        cursor.close()

        assertTrue(cursor.isClosed)
        assertTrue(cursor.requery())
        cursor.deactivate()
    }

    @Test
    fun extrasDefaultToAnEmptyBundle() {
        assertEquals(0, cursor.extras.size())
        assertNull(cursor.notificationUri)
        assertFalse(cursor.wantsAllOnMoveCalls)

        cursor.extras = Bundle().apply { putString("key", "value") }

        assertEquals("value", cursor.extras.getString("key"))

        cursor.extras = null

        assertEquals(0, cursor.extras.size())
    }

    @Test
    fun anEmptyCursorIsBothBeforeFirstAndAfterLast() {
        val empty: Cursor = TestCursor(arrayOf("name"), emptyList())

        assertTrue(empty.isBeforeFirst)
        assertTrue(empty.isAfterLast)
        assertFalse(empty.isFirst)
        assertFalse(empty.isLast)
        assertFalse(empty.moveToFirst())
    }

    private class TestCursor(
        private val columns: Array<String>,
        private val rows: List<Array<Any?>>
    ) : AbstractLocalCursor() {
        override fun getCount(): Int = rows.size

        override fun getColumnNames(): Array<String> = columns

        override fun getObject(columnIndex: Int): Any? = rows[position][columnIndex]
    }
}
