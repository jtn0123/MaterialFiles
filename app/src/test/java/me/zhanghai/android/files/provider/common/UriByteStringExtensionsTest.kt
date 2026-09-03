/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Paths are bytes, not strings, and they travel through [URI]s in intents, saved state and the
 * playback-position store. Anything that does not survive the round trip here shows up as a file
 * that "does not exist" after a rotation.
 */
class UriByteStringExtensionsTest {
    private val authority = UriAuthority(null, "", null)

    @Test
    fun plainPathIsLeftAlone() {
        val uri = URI::class.create("file", authority, "/a/b.txt".toByteString(), null)
        assertEquals("file:///a/b.txt", uri.toString())
        assertEquals("/a/b.txt".toByteString(), uri.decodedPathByteString)
        assertNull(uri.decodedQueryByteString)
    }

    @Test
    fun reservedAndNonAsciiBytesArePercentEncoded() {
        val path = "/a b/文件#1?.mp4".toByteString()
        val uri = URI::class.create("file", authority, path, null)
        assertEquals("file:///a%20b/%E6%96%87%E4%BB%B6%231%3F.mp4", uri.toString())
        assertEquals(path, uri.decodedPathByteString)
    }

    @Test
    fun invalidUtf8BytesSurviveTheRoundTrip() {
        val path = byteArrayOf('/'.code.toByte(), 0xFF.toByte(), 0xFE.toByte()).toByteString()
        val uri = URI::class.create("file", authority, path, null)
        assertEquals("file:///%FF%FE", uri.toString())
        assertEquals(path, uri.decodedPathByteString)
    }

    @Test
    fun queryIsEncodedSeparatelyFromThePath() {
        val query = "a=b&c=d/e?f g".toByteString()
        val uri = URI::class.create("archive", authority, "/x.zip".toByteString(), query)
        assertEquals("archive:///x.zip?a=b&c=d/e?f%20g", uri.toString())
        assertEquals(query, uri.decodedQueryByteString)
        assertEquals("/x.zip".toByteString(), uri.decodedPathByteString)
    }

    @Test
    fun emptyPathIsAllowed() {
        val uri = URI::class.create(
            "smb",
            UriAuthority("user", "nas.local", 445),
            ByteString.EMPTY,
            null
        )
        assertEquals("smb://user@nas.local:445", uri.toString())
        assertEquals(ByteString.EMPTY, uri.decodedPathByteString)
    }

    @Test(expected = IllegalArgumentException::class)
    fun relativePathIsRejected() {
        URI::class.create("file", authority, "a/b".toByteString(), null)
    }

    @Test
    fun authorityEncodesUserInfoAndHost() {
        assertEquals("", UriAuthority(null, "", null).encode())
        assertEquals("nas.local", UriAuthority(null, "nas.local", null).encode())
        assertEquals("u%20ser@nas.local:445", UriAuthority("u ser", "nas.local", 445).encode())
        assertEquals("u ser@nas.local:445", UriAuthority("u ser", "nas.local", 445).toString())
        assertEquals("[::1]:22", UriAuthority(null, "[::1]", 22).encode())
    }
}
