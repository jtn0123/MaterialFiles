/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb.client

import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of the addresses a name resolves to an SMB connection goes to. */
class HostAddressTest {
    private val ipv4 = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 2))

    private val ipv6 = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })

    @Test
    fun anIpv4AddressIsPreferred() {
        assertEquals("192.168.1.2", pickHostAddress("nas", listOf(ipv6, ipv4)))
    }

    @Test
    fun anIpv6AddressIsUsedWhenThereIsNoOther() {
        assertEquals(ipv6.hostAddress, pickHostAddress("nas", listOf(ipv6)))
    }

    @Test
    fun noAddressIsAnUnknownHostNotACrash() {
        val exception = assertThrows(ClientException::class.java) {
            pickHostAddress("nas", emptyList())
        }
        assertTrue(exception.cause is UnknownHostException)
    }
}
