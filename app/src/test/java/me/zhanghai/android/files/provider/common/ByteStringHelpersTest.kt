/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The [ByteString] helpers that mirror the `String` ones, down to their edge cases. */
class ByteStringHelpersTest {
    private val dot = '.'.code.toByte()
    private val slash = '/'.code.toByte()

    private fun String.bs(): ByteString = toByteString()

    @Test
    fun theWhileHelpersStopAtTheFirstByteThatDoesNotMatch() {
        val path = "//a/b//".bs()
        assertEquals("a/b//".bs(), path.dropWhile { it == slash })
        assertEquals("//a/b".bs(), path.dropLastWhile { it == slash })
        assertEquals("//".bs(), path.takeWhile { it == slash })
        assertEquals("//".bs(), path.takeLastWhile { it == slash })
    }

    @Test
    fun theWhileHelpersOnAStringThatMatchesThroughout() {
        val slashes = "///".bs()
        assertEquals(ByteString.EMPTY, slashes.dropWhile { it == slash })
        assertEquals(ByteString.EMPTY, slashes.dropLastWhile { it == slash })
        assertSame(slashes, slashes.takeWhile { it == slash })
        assertSame(slashes, slashes.takeLastWhile { it == slash })
        assertEquals(ByteString.EMPTY, ByteString.EMPTY.dropWhile { true })
    }

    @Test
    fun takeAndDropNeverGoPastEitherEnd() {
        val name = "abc".bs()
        assertEquals("abc".bs(), name.take(10))
        assertEquals("abc".bs(), name.takeLast(10))
        assertEquals(ByteString.EMPTY, name.drop(10))
        assertEquals(ByteString.EMPTY, name.dropLast(10))
        assertEquals("bc".bs(), name.takeLast(2))
        assertEquals("a".bs(), name.dropLast(2))
        assertThrows(IllegalArgumentException::class.java) { name.take(-1) }
        assertThrows(IllegalArgumentException::class.java) { name.takeLast(-1) }
        assertThrows(IllegalArgumentException::class.java) { name.drop(-1) }
        assertThrows(IllegalArgumentException::class.java) { name.dropLast(-1) }
    }

    @Test
    fun substringsAroundAByteDelimiter() {
        val name = "archive.tar.gz".bs()
        assertEquals("archive".bs(), name.substringBefore(dot))
        assertEquals("tar.gz".bs(), name.substringAfter(dot))
        assertEquals("archive.tar".bs(), name.substringBeforeLast(dot))
        assertEquals("gz".bs(), name.substringAfterLast(dot))
    }

    @Test
    fun substringsAroundAByteStringDelimiter() {
        val name = "a::b::c".bs()
        val delimiter = "::".bs()
        assertEquals("a".bs(), name.substringBefore(delimiter))
        assertEquals("b::c".bs(), name.substringAfter(delimiter))
        assertEquals("a::b".bs(), name.substringBeforeLast(delimiter))
        assertEquals("c".bs(), name.substringAfterLast(delimiter))
    }

    @Test
    fun aMissingDelimiterGivesTheWholeStringOrTheValueAskedFor() {
        val name = "README".bs()
        val missing = "?".bs()
        for (result in listOf(
            name.substringBefore(dot),
            name.substringAfter(dot),
            name.substringBeforeLast(dot),
            name.substringAfterLast(dot),
            name.substringBefore("..".bs()),
            name.substringAfter("..".bs()),
            name.substringBeforeLast("..".bs()),
            name.substringAfterLast("..".bs())
        )) {
            assertEquals(name, result)
        }
        assertEquals(missing, name.substringBefore(dot, missing))
        assertEquals(missing, name.substringAfter("..".bs(), missing))
        assertEquals(missing, name.substringBeforeLast("..".bs(), missing))
        assertEquals(missing, name.substringAfterLast(dot, missing))
    }

    @Test
    fun searchingForSomethingLongerThanTheStringFindsNothing() {
        val name = "ab".bs()
        assertEquals(-1, name.lastIndexOf("abc".bs()))
        assertEquals(-1, name.indexOf("abc".bs()))
        assertFalse(name.contains("abc".bs()))
        assertTrue(name.contains("b".bs()))
    }

    @Test
    fun bytesAreCopiedUnlessTheyAreHandedOver() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val copied = ByteString.fromBytes(bytes, 1, 3)
        val moved = bytes.moveToByteString()
        bytes[1] = 9
        assertArrayEquals(byteArrayOf(2, 3), copied.toBytes())
        assertArrayEquals(byteArrayOf(1, 9, 3, 4), moved.toBytes())
        assertEquals(dot.toByteString(), ".".bs())
    }

    @Test
    fun aCStringEndsWithANulByte() {
        assertArrayEquals(byteArrayOf(0x61, 0x62, 0), "ab".bs().cstr)
        assertArrayEquals(byteArrayOf(0), ByteString.EMPTY.cstr)
    }

    @Test
    fun aByteStringIsNeverEqualToTheStringItHolds() {
        assertFalse("abc".equals("abc".bs()))
        assertFalse("abc".bs().equals("abc"))
        assertEquals("abc".bs(), "abc".toByteArray().toByteString())
    }

    @Test
    fun anEmptyStringCanBeTurnedIntoNull() {
        assertEquals(null, ByteString.EMPTY.takeIfNotEmpty())
        assertEquals("a".bs(), "a".bs().takeIfNotEmpty())
    }
}
