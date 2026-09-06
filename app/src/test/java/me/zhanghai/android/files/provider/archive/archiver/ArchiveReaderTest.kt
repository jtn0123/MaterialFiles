/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import me.zhanghai.android.files.provider.common.TestPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The entry name normalization is the archive provider's defence against path traversal. */
class ArchiveReaderTest {
    private val root = TestPath("/")

    private fun normalize(entryName: String, isDirectory: Boolean = false) =
        ArchiveReader.normalizeEntryPath(root, entryName, isDirectory)

    @Test
    fun plainEntriesResolveUnderTheRoot() {
        assertEquals(TestPath("/a/b.txt"), normalize("a/b.txt"))
        assertEquals(TestPath("/dir"), normalize("dir/", isDirectory = true))
        assertEquals(TestPath("/x"), normalize("./x"))
    }

    @Test
    fun traversalEntriesStayUnderTheRoot() {
        val names = listOf(
            "../../etc/passwd",
            "a/../../etc/passwd",
            "/../etc/passwd",
            "./../x",
            "a/b/../../../../c"
        )
        for (name in names) {
            val path = normalize(name)!!
            assertTrue(name, path.isAbsolute)
            assertTrue(name, path.startsWith(root))
            assertFalse(name, path.toString().contains(".."))
        }
        assertEquals(TestPath("/etc/passwd"), normalize("../../etc/passwd"))
        assertEquals(TestPath("/etc/passwd"), normalize("a/../../etc/passwd"))
    }

    @Test
    fun absoluteEntryNamesAreKeptInsideTheRoot() {
        assertEquals(TestPath("/etc/passwd"), normalize("/etc/passwd"))
    }

    @Test
    fun entriesThatCollapseToTheRootAreDropped() {
        assertNull(normalize("a/.."))
        assertNull(normalize("./"))
        assertNull(normalize("."))
        assertNull(normalize("a/../b/../"))
    }

    @Test
    fun onlyADirectoryEntryMayBeTheRootItself() {
        assertNull(normalize("", isDirectory = false))
        assertEquals(root, normalize("", isDirectory = true))
    }
}
