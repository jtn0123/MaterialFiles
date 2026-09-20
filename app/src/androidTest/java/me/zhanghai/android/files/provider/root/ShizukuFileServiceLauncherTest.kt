/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.content.ServiceConnection
import android.os.Binder
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import me.zhanghai.android.files.provider.remote.IRemoteFileService
import me.zhanghai.android.files.provider.remote.RemoteFileSystemException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Neither Sui nor the Shizuku app is installed on the emulator, so the launcher must say so instead
 * of trying to bind. The binding callbacks are checked directly.
 */
@RunWith(AndroidJUnit4::class)
class ShizukuFileServiceLauncherTest {
    @Test
    fun shizukuIsNotAvailableWithoutSuiOrTheShizukuApp() {
        // Also initializes Sui, which must not throw when it isn't there.
        assertFalse(ShizukuFileServiceLauncher.isShizukuAvailable())
        assertFalse(
            "Initializing Sui twice must not change the answer",
            ShizukuFileServiceLauncher.isShizukuAvailable()
        )
    }

    @Test
    fun launchingWithoutShizukuFailsInsteadOfBinding() {
        val exception = runCatching { ShizukuFileServiceLauncher.launchService() }.exceptionOrNull()

        assertTrue(
            "Expected a RemoteFileSystemException but got $exception",
            exception is RemoteFileSystemException
        )
        assertEquals("Shizuku isn't available", exception!!.message)
    }

    @Test
    fun aConnectedServiceResumesWithTheRemoteInterface() {
        val binder = Binder()

        val service = bindWith { it.onServiceConnected(null, binder) }.getOrThrow()

        assertSame(binder, service.asBinder())
    }

    @Test
    fun eachFailedBindingResumesWithItsOwnMessage() {
        assertEquals(
            "Shizuku service disconnected",
            bindFailureMessage { it.onServiceDisconnected(null) }
        )
        assertEquals("Shizuku binding died", bindFailureMessage { it.onBindingDied(null) })
        assertEquals("Shizuku binding is null", bindFailureMessage { it.onNullBinding(null) })
    }

    @Test
    fun aFailureAfterTheServiceConnectedIsIgnored() {
        val binder = Binder()

        val service = bindWith {
            it.onServiceConnected(null, binder)
            // Shizuku unbinds the service once we are done with it.
            it.onServiceDisconnected(null)
        }.getOrThrow()

        assertSame(binder, service.asBinder())
    }

    private fun bindFailureMessage(action: (ServiceConnection) -> Unit): String? {
        val exception = bindWith(action).exceptionOrNull()
        assertTrue(
            "Expected a RemoteFileSystemException but got $exception",
            exception is RemoteFileSystemException
        )
        return exception!!.message
    }

    private fun bindWith(action: (ServiceConnection) -> Unit): Result<IRemoteFileService> =
        runBlocking {
            runCatching {
                suspendCancellableCoroutine { continuation ->
                    action(ContinuationServiceConnection(continuation))
                }
            }
        }
}
