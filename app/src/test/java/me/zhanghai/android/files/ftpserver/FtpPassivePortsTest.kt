/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ftpserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtpPassivePortsTest {
    @Test
    fun blankMeansAnyPort() {
        assertEquals("", FtpPassivePorts.normalize(""))
        assertEquals("", FtpPassivePorts.normalize("   "))
    }

    @Test
    fun acceptsPortsAndRanges() {
        assertEquals("2300", FtpPassivePorts.normalize("2300"))
        assertEquals("2300-2400", FtpPassivePorts.normalize("2300-2400"))
        assertEquals("2300-2301, 2400-2500", FtpPassivePorts.normalize("2300,2301,2400-2500"))
        assertEquals("1-65535", FtpPassivePorts.normalize(" 1 - 65535 "))
    }

    @Test
    fun sortsMergesAndDeduplicates() {
        assertEquals("2300-2401", FtpPassivePorts.normalize("2400-2401, 2300-2399, 2350"))
        assertEquals("2300, 2302", FtpPassivePorts.normalize("2302, 2300, 2300"))
    }

    @Test
    fun rejectsAnythingElse() {
        assertNull(FtpPassivePorts.normalize("0"))
        assertNull(FtpPassivePorts.normalize("65536"))
        assertNull(FtpPassivePorts.normalize("2400-2300"))
        assertNull(FtpPassivePorts.normalize("2300-"))
        assertNull(FtpPassivePorts.normalize("-2300"))
        assertNull(FtpPassivePorts.normalize("2300-2400-2500"))
        assertNull(FtpPassivePorts.normalize("2300,,2301"))
        assertNull(FtpPassivePorts.normalize("abc"))
        assertNull(FtpPassivePorts.normalize("+2300"))
        assertNull(FtpPassivePorts.normalize("2300;2301"))
    }
}
