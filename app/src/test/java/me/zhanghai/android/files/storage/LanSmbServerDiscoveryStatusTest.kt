/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import java.io.IOException
import java.net.InetAddress
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Success
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LanSmbServerDiscoveryStatusTest {
    private val server = LanSmbServer("NAS", InetAddress.getByName("192.168.1.10"))

    @Test
    fun nothingIsSaidWhileSearching() {
        assertNull(LanSmbServerDiscoveryStatus.of(Loading(null)))
        assertNull(LanSmbServerDiscoveryStatus.of(Loading(emptyList())))
        assertNull(LanSmbServerDiscoveryStatus.of(Loading(listOf(server))))
    }

    @Test
    fun aSearchThatFoundNothingSaysSo() {
        assertSame(
            LanSmbServerDiscoveryStatus.NoneFound,
            LanSmbServerDiscoveryStatus.of(Success(emptyList()))
        )
    }

    @Test
    fun serversFoundNeedNoExplanation() {
        assertNull(LanSmbServerDiscoveryStatus.of(Success(listOf(server))))
    }

    @Test
    fun beingOffALocalNetworkIsNotAnError() {
        assertSame(
            LanSmbServerDiscoveryStatus.NotOnLocalNetwork,
            LanSmbServerDiscoveryStatus.of(Failure(null, NotOnLocalNetworkException()))
        )
    }

    @Test
    fun aFailedSearchKeepsWhatWentWrong() {
        val exception = IOException("Network is unreachable")
        assertEquals(
            LanSmbServerDiscoveryStatus.Failed(exception),
            LanSmbServerDiscoveryStatus.of(Failure(listOf(server), exception))
        )
    }

    @Test
    fun onlyAnIpv4AddressOnALocalNetworkCanBeSearchedAround() {
        assertTrue(InetAddress.getByName("192.168.1.5").isSearchableLocalAddress)
        assertTrue(InetAddress.getByName("10.0.2.15").isSearchableLocalAddress)
        assertTrue(InetAddress.getByName("172.16.0.3").isSearchableLocalAddress)
        // A mobile network hands out public addresses, or none at all.
        assertFalse(InetAddress.getByName("100.64.1.2").isSearchableLocalAddress)
        assertFalse(InetAddress.getByName("8.8.8.8").isSearchableLocalAddress)
        assertFalse(InetAddress.getByName("fec0::1").isSearchableLocalAddress)
        assertFalse(null.isSearchableLocalAddress)
    }
}
