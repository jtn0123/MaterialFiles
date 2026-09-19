/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav.client

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HttpUrlExtensionsTest {
    @Test
    fun addsATrailingSlashToACollection() {
        assertEquals(
            "https://dav.example/base/dir/",
            "https://dav.example/base/dir".toHttpUrl().toCollectionUrl().toString()
        )
    }

    @Test
    fun keepsAnExistingTrailingSlash() {
        val url = "https://dav.example/base/dir/".toHttpUrl()
        assertSame(url, url.toCollectionUrl())
        assertEquals(
            "https://dav.example/",
            "https://dav.example/".toHttpUrl().toCollectionUrl().toString()
        )
    }

    @Test
    fun keepsEncodingQueryAndPort() {
        assertEquals(
            "http://dav.example:8081/a%20b/c%2Fd/?x=1",
            "http://dav.example:8081/a%20b/c%2Fd?x=1".toHttpUrl().toCollectionUrl().toString()
        )
    }
}
