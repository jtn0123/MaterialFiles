/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.InvalidPathException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every provider (local, archive, SMB, SFTP, FTP, WebDAV, document) inherits its path arithmetic
 * from [ByteStringListPath], so these rules are what "copy into", "is ancestor of" and the
 * breadcrumbs rely on.
 */
class ByteStringListPathTest {
    private fun path(value: String) = TestPath(value)

    @Test
    fun parsingCollapsesRepeatedSeparators() {
        assertEquals("/a/b", path("//a///b/").toString())
        assertEquals("a/b", path("a//b").toString())
        assertEquals("/", path("///").toString())
        assertEquals("", path("").toString())
    }

    @Test
    fun absoluteAndRelative() {
        assertTrue(path("/a").isAbsolute)
        assertFalse(path("a").isAbsolute)
        assertFalse(path("").isAbsolute)
        assertEquals(path("/"), path("/a").root)
        assertNull(path("a").root)
        assertEquals(path("/a"), path("a").toAbsolutePath())
        assertEquals(path("/a"), path("/a").toAbsolutePath())
    }

    @Test(expected = InvalidPathException::class)
    fun nulCharacterIsRejected() {
        path("a\u0000b")
    }

    @Test
    fun namesAndParents() {
        val p = path("/a/b/c")
        assertEquals(3, p.nameCount)
        assertEquals(path("a"), p.getName(0))
        assertEquals(path("c"), p.fileName)
        assertEquals(path("/a/b"), p.parent)
        assertEquals(path("/a"), p.parent!!.parent)
        assertEquals(path("/"), p.parent!!.parent!!.parent)
        assertNull(path("/").parent)
        assertNull(path("/").fileName)
        assertNull(path("a").parent)
        assertEquals(path("b/c"), p.subpath(1, 3))
        assertEquals(listOf("a", "b", "c"), p.names.map { it.toString() })
    }

    @Test
    fun startsWithIsSegmentBased() {
        assertTrue(path("/a/b").startsWith(path("/a")))
        assertTrue(path("/a/b").startsWith(path("/a/b")))
        assertFalse(path("/a/b").startsWith(path("/a/b/c")))
        // "/ab" does not start with "/a": prefixes are whole names, not characters.
        assertFalse(path("/ab").startsWith(path("/a")))
        assertFalse(path("/a").startsWith(path("a")))
        assertTrue(path("a/b").startsWith(path("a")))
        assertTrue(path("/a/b").startsWith("/a"))
    }

    @Test
    fun endsWithIsSegmentBased() {
        assertTrue(path("/a/b/c").endsWith(path("b/c")))
        assertTrue(path("/a/b/c").endsWith(path("/a/b/c")))
        assertFalse(path("/a/b/c").endsWith(path("/b/c")))
        assertFalse(path("/a/bc").endsWith(path("c")))
        assertTrue(path("/a/b/c").endsWith("c"))
    }

    @Test
    fun normalizeRemovesDotsWithinTheRoot() {
        assertEquals(path("/a/c"), path("/a/./b/../c").normalize())
        assertEquals(path("/"), path("/..").normalize())
        assertEquals(path("/"), path("/a/../..").normalize())
        assertEquals(path("../a"), path("../a").normalize())
        assertEquals(path("../../a"), path("../b/../../a").normalize())
        assertEquals(path(""), path("a/..").normalize())
        assertEquals(path(""), path("./.").normalize())
        assertEquals(path("a/b"), path("./a/./b/.").normalize())
    }

    @Test
    fun resolve() {
        assertEquals(path("/a/b"), path("/a").resolve(path("b")))
        assertEquals(path("/a/b/c"), path("/a").resolve("b/c"))
        assertEquals(path("/b"), path("/a").resolve(path("/b")))
        assertEquals(path("/a"), path("/a").resolve(path("")))
        assertEquals(path("b"), path("").resolve(path("b")))
        assertEquals(path("a/b"), path("a").resolve(path("b")))
        assertEquals(path("/a/b"), path("/a").resolve("b".toByteString()))
        // Resolving does not normalize.
        assertEquals(path("/a/../b"), path("/a").resolve("../b"))
    }

    @Test
    fun resolveSibling() {
        assertEquals(path("/a/c"), path("/a/b").resolveSibling(path("c")))
        assertEquals(path("/a/c"), path("/a/b").resolveSibling("c"))
        assertEquals(path("/a/c"), path("/a/b").resolveSibling("c".toByteString()))
        assertEquals(path("c"), path("/").resolveSibling(path("c")))
        assertEquals(path("c"), path("b").resolveSibling(path("c")))
    }

    @Test
    fun relativize() {
        assertEquals(path("b/c"), path("/a").relativize(path("/a/b/c")))
        assertEquals(path("../c"), path("/a/b").relativize(path("/a/c")))
        assertEquals(path("../.."), path("/a/b").relativize(path("/")))
        assertEquals(path(""), path("/a").relativize(path("/a")))
        assertEquals(path("../../x/y"), path("a/b").relativize(path("x/y")))
        assertEquals(path("x"), path("").relativize(path("x")))
        // Resolving the relative form back onto the base must give the original target.
        val base = path("/Movies/Shows")
        val target = path("/Movies/Films/a.mp4")
        assertEquals(target, base.resolve(base.relativize(target)).normalize())
    }

    @Test(expected = IllegalArgumentException::class)
    fun relativizeRequiresMatchingAbsoluteness() {
        path("/a").relativize(path("a"))
    }

    @Test
    fun equalityAndOrdering() {
        assertEquals(path("/a/b"), path("/a//b/"))
        assertEquals(path("/a/b").hashCode(), path("/a//b/").hashCode())
        assertNotEquals(path("/a"), path("a"))
        assertNotEquals(path("/a"), path("/a/b"))
        assertTrue(path("/a") < path("/b"))
        assertTrue(path("/a") < path("/a/b"))
        assertTrue(path("/a/b") < path("/a/c"))
        assertEquals(0, path("/a").compareTo(path("/a")))
        val sorted = listOf(path("/b"), path("/a/c"), path("/a"), path("/a/b")).sorted()
        assertEquals(listOf("/a", "/a/b", "/a/c", "/b"), sorted.map { it.toString() })
    }

    @Test
    fun toByteStringRoundTrips() {
        for (value in listOf("/", "/a", "/a/b", "a", "a/b", "")) {
            assertEquals(value, path(value).toByteString().toString())
            assertEquals(path(value), TestPath(path(value).toString()))
        }
    }
}
