/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeTypeTest {
    @Test
    fun partsOfPlainType() {
        "image/png".asMimeType().let {
            assertEquals("image", it.type)
            assertEquals("png", it.subtype)
            assertNull(it.suffix)
            assertNull(it.parameters)
        }
    }

    @Test
    fun partsOfTypeWithSuffix() {
        "image/svg+xml".asMimeType().let {
            assertEquals("image", it.type)
            assertEquals("svg+xml", it.subtype)
            assertEquals("xml", it.suffix)
        }
    }

    @Test
    fun partsOfTypeWithParameters() {
        "text/plain; charset=utf-8".asMimeType().let {
            assertEquals("text", it.type)
            assertEquals("plain", it.subtype)
            assertEquals(" charset=utf-8", it.parameters)
            assertNull(it.suffix)
        }
    }

    @Test
    fun plusInsideParametersIsNotASuffix() {
        assertNull("text/plain;a=b+c".asMimeType().suffix)
        assertEquals("xml", "application/atom+xml;a=b+c".asMimeType().suffix)
    }

    @Test
    fun wildcardMatching() {
        val png = "image/png".asMimeType()
        assertTrue(MimeType.ANY.match(png))
        assertTrue(MimeType.IMAGE_ANY.match(png))
        assertTrue(png.match(png))
        assertFalse("image/jpeg".asMimeType().match(png))
        assertFalse(MimeType.IMAGE_ANY.match("video/mp4".asMimeType()))
    }

    @Test
    fun parametersMustMatchWhenPresent() {
        val plain = "text/plain".asMimeType()
        val utf8 = "text/plain;charset=utf-8".asMimeType()
        // A pattern without parameters matches any parameters.
        assertTrue(plain.match(utf8))
        // A pattern with parameters only matches the same parameters.
        assertFalse(utf8.match(plain))
        assertTrue(utf8.match(utf8))
    }

    @Test
    fun invalidStringsAreRejected() {
        assertNull("".asMimeTypeOrNull())
        assertNull("image".asMimeTypeOrNull())
        assertNull("/png".asMimeTypeOrNull())
        // An empty subtype is deliberately tolerated.
        assertNotNull("image/".asMimeTypeOrNull())
    }
}
