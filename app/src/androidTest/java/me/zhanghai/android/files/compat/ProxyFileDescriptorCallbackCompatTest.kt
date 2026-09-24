/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.compat

import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The callback a file is handed to the system through: whatever it does not implement has to
 * report a plain errno, because the platform only lets [ErrnoException] out of it.
 */
@RunWith(AndroidJUnit4::class)
class ProxyFileDescriptorCallbackCompatTest {
    @Test
    fun whatIsNotImplementedReportsAnErrno() {
        var released = false
        val callback = object : ProxyFileDescriptorCallbackCompat() {
            override fun onRelease() {
                released = true
            }
        }

        assertErrno(OsConstants.EBADF) { callback.onGetSize() }
        assertErrno(OsConstants.EBADF) { callback.onRead(0, 1, ByteArray(1)) }
        assertErrno(OsConstants.EBADF) { callback.onWrite(0, 1, ByteArray(1)) }
        assertErrno(OsConstants.EINVAL) { callback.onFsync() }

        callback.onRelease()

        assertTrue(released)
    }

    @Test
    fun thePlatformCallbackForwardsEverything() {
        val calls = mutableListOf<String>()
        val callback = object : ProxyFileDescriptorCallbackCompat() {
            override fun onGetSize(): Long {
                calls += "onGetSize"
                return 42
            }

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                calls += "onRead($offset, $size)"
                data[0] = 1
                return 1
            }

            override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
                calls += "onWrite($offset, $size, ${data[0]})"
                return size
            }

            override fun onFsync() {
                calls += "onFsync"
            }

            override fun onRelease() {
                calls += "onRelease"
            }
        }

        val platformCallback = callback.toProxyFileDescriptorCallback()

        assertEquals(42L, platformCallback.onGetSize())
        val read = ByteArray(1)
        assertEquals(1, platformCallback.onRead(1, 1, read))
        assertArrayEquals(byteArrayOf(1), read)
        assertEquals(2, platformCallback.onWrite(2, 2, byteArrayOf(3, 4)))
        platformCallback.onFsync()
        platformCallback.onRelease()

        assertEquals(
            listOf("onGetSize", "onRead(1, 1)", "onWrite(2, 2, 3)", "onFsync", "onRelease"),
            calls
        )
    }

    private fun assertErrno(errno: Int, block: () -> Unit) {
        try {
            block()
            fail("expected ErrnoException")
        } catch (e: ErrnoException) {
            assertEquals(errno, e.errno)
        }
    }
}
