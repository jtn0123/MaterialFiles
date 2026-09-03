/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.compat

import android.webkit.MimeTypeMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

/**
 * Pins the extension table after it was split across the `MimeTypeMapCompatData*` files, so a
 * change in how the parts are concatenated cannot silently change a lookup or its precedence.
 */
class MimeTypeMapCompatTest {
    private val parts =
        listOf(
            extensionToMimeTypeMapData1,
            extensionToMimeTypeMapData2,
            extensionToMimeTypeMapData3,
            extensionToMimeTypeMapData4
        )

    // The unit test android.jar constructor throws, so allocate an instance without running it.
    // Its methods throw as well, which the fallback test relies on.
    private val mimeTypeMap: MimeTypeMap =
        Unsafe::class.java
            .getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null)
            .let { it as Unsafe }
            .allocateInstance(MimeTypeMap::class.java) as MimeTypeMap

    @Test
    fun tableLookups() {
        assertEquals("video/mp4", mimeTypeMap.getMimeTypeFromExtensionCompat("mp4"))
        assertEquals("application/x-subrip", mimeTypeMap.getMimeTypeFromExtensionCompat("srt"))
        assertEquals(
            "application/vnd.android.package-archive",
            mimeTypeMap.getMimeTypeFromExtensionCompat("apk")
        )
        assertEquals("image/jpeg", mimeTypeMap.getMimeTypeFromExtensionCompat("jpeg"))
        assertEquals("video/mp2ts", mimeTypeMap.getMimeTypeFromExtensionCompat("ts"))
        // Lookups are case-sensitive, as before the split.
        assertEquals("audio/AMR", mimeTypeMap.getMimeTypeFromExtensionCompat("AMR"))
        assertEquals("audio/amr", mimeTypeMap.getMimeTypeFromExtensionCompat("amr"))
    }

    @Test
    fun tableBoundaries() {
        // First and last entries overall, and the entries on either side of each part boundary.
        assertEquals("application/x-trash", mimeTypeMap.getMimeTypeFromExtensionCompat("%"))
        assertEquals("application/x-trash", mimeTypeMap.getMimeTypeFromExtensionCompat("~"))
        for (part in parts) {
            val (firstExtension, firstMimeType) = part.first()
            assertEquals(firstMimeType, mimeTypeMap.getMimeTypeFromExtensionCompat(firstExtension))
            val (lastExtension, lastMimeType) = part.last()
            assertEquals(lastMimeType, mimeTypeMap.getMimeTypeFromExtensionCompat(lastExtension))
        }
    }

    @Test
    fun partsAreDisjointAndComplete() {
        val extensions = parts.flatMap { part -> part.map { it.first } }
        assertEquals(1601, extensions.size)
        assertEquals(extensions.size, extensions.toSet().size)
        assertEquals(extensions, parts.flatten().toMap().keys.toList())
    }

    @Test
    fun unknownExtensionFallsBackToPlatformMap() {
        // The platform lookup is only reached for a miss; the unit test android.jar throws when
        // it is, naming the method.
        val e =
            assertThrows(RuntimeException::class.java) {
                mimeTypeMap.getMimeTypeFromExtensionCompat("no-such-extension")
            }
        assertTrue(e.message!!, "getMimeTypeFromExtension" in e.message!!)
    }
}
