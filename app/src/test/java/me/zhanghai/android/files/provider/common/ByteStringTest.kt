/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteStringTest {
    private val slash = '/'.code.toByte()
    private val dot = '.'.code.toByte()

    @Test
    fun stringRoundTripKeepsNonAsciiBytes() {
        val text = "Ünïcödé 文件.mp4"
        val byteString = text.toByteString()
        assertEquals(text, byteString.toString())
        assertEquals(text.toByteArray().size, byteString.length)
        assertTrue(text.toByteArray().contentEquals(byteString.toBytes()))
    }

    @Test
    fun invalidUtf8DoesNotThrow() {
        val bytes = byteArrayOf(0x61, 0xFF.toByte(), 0x62)
        val byteString = bytes.toByteString()
        // Undecodable bytes become the replacement character rather than an exception.
        assertEquals(3, byteString.length)
        assertEquals("a�b", byteString.toString())
    }

    @Test
    fun startsWithAndEndsWith() {
        val path = "/a/b/c.txt".toByteString()
        assertTrue(path.startsWith("/a".toByteString()))
        assertTrue(path.startsWith("b/c".toByteString(), 3))
        assertFalse(path.startsWith("/a/b/c.txt/".toByteString()))
        assertTrue(path.endsWith(".txt".toByteString()))
        assertFalse(path.endsWith("txt/".toByteString()))
        assertTrue(path.startsWith(ByteString.EMPTY))
        assertTrue(path.endsWith(ByteString.EMPTY))
    }

    @Test
    fun indexOfAndLastIndexOf() {
        val path = "/a/b/c.tar.gz".toByteString()
        assertEquals(0, path.indexOf(slash))
        assertEquals(2, path.indexOf(slash, 1))
        assertEquals(4, path.lastIndexOf(slash))
        assertEquals(-1, path.indexOf('x'.code.toByte()))
        assertEquals(6, path.indexOf(".".toByteString()))
        assertEquals(10, path.lastIndexOf(".".toByteString()))
        assertEquals(6, path.lastIndexOf(".".toByteString(), 9))
        assertEquals(-1, path.indexOf("zz".toByteString()))
        assertTrue(path.contains("tar".toByteString()))
    }

    @Test
    fun substringSharesTheWholeStringAndCopiesParts() {
        val whole = "abc".toByteString()
        assertSame(whole, whole.substring(0))
        assertEquals("bc".toByteString(), whole.substring(1))
        assertEquals("b".toByteString(), whole.substring(1, 2))
        assertEquals("ab".toByteString(), whole.substring(0..1))
        assertEquals(ByteString.EMPTY, whole.substring(3))
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun substringRejectsReversedRange() {
        "abc".toByteString().substring(2, 1)
    }

    @Test
    fun plusAndSplit() {
        val joined = "a".toByteString() + "/".toByteString() + "b".toByteString()
        assertEquals("a/b".toByteString(), joined)
        assertSame(joined, joined + ByteString.EMPTY)
        assertEquals(
            listOf("", "a", "b", "").map { it.toByteString() },
            "/a/b/".toByteString().split("/".toByteString())
        )
        assertEquals(listOf("abc".toByteString()), "abc".toByteString().split("/".toByteString()))
    }

    @Test
    fun substringHelpersMirrorTheStringOnes() {
        val name = "archive.tar.gz".toByteString()
        assertEquals("archive".toByteString(), name.substringBefore(dot))
        assertEquals("tar.gz".toByteString(), name.substringAfter(dot))
        assertEquals("archive.tar".toByteString(), name.substringBeforeLast(dot))
        assertEquals("gz".toByteString(), name.substringAfterLast(dot))
        assertEquals("tar.gz".toByteString(), name.substringAfter("archive.".toByteString()))
        val plain = "README".toByteString()
        assertSame(plain, plain.substringAfterLast(dot))
        assertEquals(ByteString.EMPTY, plain.substringAfterLast(dot, ByteString.EMPTY))
    }

    @Test
    fun takeAndDrop() {
        val name = "abc.txt".toByteString()
        assertEquals("abc".toByteString(), name.take(3))
        assertEquals("txt".toByteString(), name.takeLast(3))
        assertEquals(".txt".toByteString(), name.drop(3))
        assertEquals("abc".toByteString(), name.dropLast(4))
        assertEquals("abc".toByteString(), name.takeWhile { it != dot })
        assertEquals("txt".toByteString(), name.takeLastWhile { it != dot })
        assertEquals("abc.".toByteString(), name.dropLastWhile { it != dot })
        assertEquals("a".toByteString(), "///a".toByteString().dropWhile { it == slash })
    }

    @Test
    fun equalityAndOrderingAreByteWise() {
        assertEquals("a".toByteString(), "a".toByteString())
        assertEquals("a".toByteString().hashCode(), "a".toByteString().hashCode())
        assertNotEquals("a".toByteString(), "b".toByteString())
        assertTrue("a".toByteString() < "b".toByteString())
        assertTrue("a".toByteString() < "ab".toByteString())
        assertEquals(0, "ab".toByteString().compareTo("ab".toByteString()))
    }

    @Test
    fun toBytesCopiesButBorrowDoesNot() {
        val byteString = "abc".toByteString()
        val copy = byteString.toBytes()
        copy[0] = 'x'.code.toByte()
        assertEquals("abc", byteString.toString())
        assertSame(byteString.borrowBytes(), byteString.borrowBytes())
    }

    @Test
    fun nullOrEmptyHelpers() {
        assertTrue((null as ByteString?).isNullOrEmpty())
        assertTrue(ByteString.EMPTY.isNullOrEmpty())
        assertFalse("a".toByteString().isNullOrEmpty())
        assertNull(ByteString.EMPTY.takeIfNotEmpty())
        assertEquals("a".toByteString(), "a".toByteString().takeIfNotEmpty())
    }

    @Test
    fun builderConcatenates() {
        val built = ByteStringBuilder()
            .append("a".toByteString())
            .append(slash)
            .append("b".toByteString())
            .toByteString()
        assertEquals("a/b".toByteString(), built)
    }
}
