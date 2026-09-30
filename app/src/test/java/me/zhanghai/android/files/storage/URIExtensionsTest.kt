/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import java.net.URI
import me.zhanghai.android.files.util.recordWarnings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A server address typed into a form becomes a URI, or nothing and a line in the log. */
class URIExtensionsTest {
    @Test
    fun aValidAddressBecomesAUri() {
        val uri = URI::class.createOrLog("sftp", "user", "nas.local", 22, "/home", null, null)
        assertEquals("sftp://user@nas.local:22/home", uri.toString())
        assertTrue(URI::class.isValidHost("nas.local"))
    }

    @Test
    fun anInvalidHostIsLoggedAndGivesNoUri() {
        var uri: URI? = URI.create("placeholder:x")
        val warnings = recordWarnings {
            uri = URI::class.createOrLog("sftp", null, "nas local", 22, null, null, null)
        }
        assertNull(uri)
        assertEquals("Create the URI for host nas local", warnings.single().operation)
        assertFalse(URI::class.isValidHost("nas local"))
    }

    @Test
    fun anInvalidUriStringIsLoggedAndGivesNoUri() {
        var uri: URI? = URI.create("placeholder:x")
        val warnings = recordWarnings { uri = URI::class.createOrLog("sftp://nas local/") }
        assertNull(uri)
        assertEquals("URIExtensions", warnings.single().tag)
    }
}
