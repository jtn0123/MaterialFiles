/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import me.zhanghai.android.files.provider.common.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The byte-based twins of [FileName] and [PathName] must agree with the string versions. */
class BytePathNameTest {
    private fun b(value: String) = value.toByteString()

    @Test
    fun extensionsMatchTheStringVersion() {
        val names = listOf(
            "Movie.mp4", "archive.tar.gz", "ARCHIVE.TAR.GZ", "Movie.en.srt", "a.b.c",
            "archive.gz", "README", "name.", "文件.txt"
        )
        for (name in names) {
            val string = name.asFileName()
            val bytes = b(name).asFileName()
            assertEquals(name, string.singleExtension, bytes.singleExtension.toString())
            assertEquals(name, string.extensions, bytes.extensions.toString())
            assertEquals(name, string.baseName, bytes.baseName.toString())
        }
    }

    @Test
    fun invalidFileNamesAreRejected() {
        assertNull(b("").asFileNameOrNull())
        assertNull(b("a/b").asFileNameOrNull())
        assertNull(b("a\u0000b").asFileNameOrNull())
        assertEquals(b("a b"), b("a b").asFileNameOrNull()?.value)
    }

    @Test
    fun pathNameSplitsFileAndDirectory() {
        for (path in listOf("/a/b/c", "/a/b/", "/a", "a", "/", "a//b", "文件夹/文件")) {
            val string = path.asPathName()
            val bytes = b(path).asPathName()
            assertEquals(path, string.fileName, bytes.fileName?.toString())
            assertEquals(path, string.directoryName, bytes.directoryName?.toString())
        }
    }

    @Test
    fun invalidPathNamesAreRejected() {
        assertNull(b("").asPathNameOrNull())
        assertNull(b("a\u0000b").asPathNameOrNull())
        assertEquals(b("/a"), b("/a").asPathNameOrNull()?.value)
    }
}
