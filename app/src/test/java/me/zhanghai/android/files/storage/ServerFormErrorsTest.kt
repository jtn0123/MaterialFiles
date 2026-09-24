/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerFormErrorsTest {
    private val errors = ServerFormErrors<String>()

    @Test
    fun aValidFormHasNoErrors() {
        assertEquals("example.com", errors.checkHost("example.com", "host", EMPTY, INVALID))
        assertEquals(2121, errors.checkPort("2121", 21, "port", INVALID))
        assertEquals("tester", errors.checkNotEmpty("tester", "username", EMPTY))

        assertTrue(errors.isEmpty)
        assertEquals(emptyList<Pair<String, Int>>(), errors.errors)
    }

    @Test
    fun anEmptyHostIsAnError() {
        assertNull(errors.checkHost("", "host", EMPTY, INVALID))

        assertEquals(listOf("host" to EMPTY), errors.errors)
    }

    @Test
    fun anInvalidHostIsAnErrorButStillReturned() {
        assertEquals("bad host", errors.checkHost("bad host", "host", EMPTY, INVALID))

        assertEquals(listOf("host" to INVALID), errors.errors)
    }

    @Test
    fun anIpv6HostIsBracketed() {
        assertEquals("[fe80::1]", errors.checkHost("fe80::1", "host", EMPTY, INVALID))
        assertEquals("[::1]", errors.checkHost("[::1]", "host", EMPTY, INVALID))

        assertTrue(errors.isEmpty)
    }

    @Test
    fun anEmptyPortIsTheDefault() {
        assertEquals(22, errors.checkPort("", 22, "port", INVALID))

        assertTrue(errors.isEmpty)
    }

    @Test
    fun aPortThatIsNotANumberIsAnError() {
        assertNull(errors.checkPort("ssh", 22, "port", INVALID))
        assertNull(errors.checkPort("99999999999", 22, "port", INVALID))

        assertEquals(listOf("port" to INVALID, "port" to INVALID), errors.errors)
    }

    @Test
    fun anEmptyRequiredFieldIsAnError() {
        assertNull(errors.checkNotEmpty("", "username", EMPTY))

        assertFalse(errors.isEmpty)
        assertEquals(listOf("username" to EMPTY), errors.errors)
    }

    @Test
    fun errorsKeepTheOrderTheFieldsWereCheckedIn() {
        errors.checkHost("", "host", EMPTY, INVALID)
        errors.checkPort("x", 21, "port", INVALID)
        errors.add("password", OTHER)
        errors.checkNotEmpty("", "username", EMPTY)

        assertEquals(
            listOf("host" to EMPTY, "port" to INVALID, "password" to OTHER, "username" to EMPTY),
            errors.errors
        )
    }

    companion object {
        private const val EMPTY = 1
        private const val INVALID = 2
        private const val OTHER = 3
    }
}
