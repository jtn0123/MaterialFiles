/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import androidx.annotation.Keep
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.system.exitProcess
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.remote.IRemoteFileService
import me.zhanghai.android.files.provider.remote.RemoteFileServiceInterface
import me.zhanghai.android.files.provider.remote.RemoteFileSystemException
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import rikka.sui.Sui

/**
 * Runs the root file service through the Shizuku API, which is served either by Sui (a Magisk
 * module, in-process) or by the Shizuku app (which hands its binder to [ShizukuProvider] in the
 * manifest). Both are reached through the same `Shizuku` class once Sui has had its chance to
 * initialize.
 */
object ShizukuFileServiceLauncher {
    private val lock = Any()

    private var isSuiInitialized = false

    fun isShizukuAvailable(): Boolean {
        synchronized(lock) {
            if (!isSuiInitialized) {
                // Sui injects its binder here; with the Shizuku app the provider receives it.
                Sui.init(application.packageName)
                isSuiInitialized = true
            }
            return Shizuku.pingBinder()
        }
    }

    @Throws(RemoteFileSystemException::class)
    fun launchService(): IRemoteFileService {
        synchronized(lock) {
            if (!isShizukuAvailable()) {
                throw RemoteFileSystemException("Shizuku isn't available")
            }
            ensurePermissionGranted()
            return bindService()
        }
    }

    @Throws(RemoteFileSystemException::class)
    private fun ensurePermissionGranted() {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            return
        }
        val granted = try {
            runBlocking { requestPermission() }
        } catch (e: InterruptedException) {
            throw RemoteFileSystemException(e)
        }
        if (!granted) {
            throw RemoteFileSystemException("Shizuku permission isn't granted")
        }
    }

    private suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { continuation ->
        lateinit var listener: Shizuku.OnRequestPermissionResultListener
        listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            Shizuku.removeRequestPermissionResultListener(listener)
            continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
        }
        Shizuku.addRequestPermissionResultListener(listener)
        continuation.invokeOnCancellation {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
        Shizuku.requestPermission(listener.hashCode())
    }

    @Throws(RemoteFileSystemException::class)
    private fun bindService(): IRemoteFileService = try {
        runBlocking {
            try {
                withTimeout(RootFileService.TIMEOUT_MILLIS) { bindUserService() }
            } catch (e: TimeoutCancellationException) {
                throw RemoteFileSystemException(e)
            }
        }
    } catch (e: InterruptedException) {
        throw RemoteFileSystemException(e)
    }

    private suspend fun bindUserService(): IRemoteFileService =
        suspendCancellableCoroutine { continuation ->
            val serviceArgs = createUserServiceArgs()
            val connection = ContinuationServiceConnection(continuation)
            Shizuku.bindUserService(serviceArgs, connection)
            continuation.invokeOnCancellation {
                Shizuku.unbindUserService(serviceArgs, connection, true)
            }
        }

    private fun createUserServiceArgs(): Shizuku.UserServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(application, ShizukuFileServiceInterface::class.java)
    )
        .debuggable(BuildConfig.DEBUG)
        .daemon(false)
        .processNameSuffix("shizuku")
        .version(BuildConfig.VERSION_CODE)
}

/** Resumes [continuation] with the bound service, or fails it when the binding doesn't work out. */
internal class ContinuationServiceConnection(
    private val continuation: CancellableContinuation<IRemoteFileService>
) : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder) {
        continuation.resume(IRemoteFileService.Stub.asInterface(service))
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        fail("Shizuku service disconnected")
    }

    override fun onBindingDied(name: ComponentName?) {
        fail("Shizuku binding died")
    }

    override fun onNullBinding(name: ComponentName?) {
        fail("Shizuku binding is null")
    }

    private fun fail(message: String) {
        if (continuation.isActive) {
            continuation.resumeWithException(RemoteFileSystemException(message))
        }
    }
}

@Keep
class ShizukuFileServiceInterface : RemoteFileServiceInterface() {
    init {
        RootFileService.main()
    }

    /**
     * Shizuku asks a user service to go away with a transaction of its own rather than killing the
     * process, so the process lingers unless it exits here.
     */
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        // Let super call data.enforceInterface() exactly once.
        if (super.onTransact(code, data, reply, flags)) {
            return true
        }
        if (code != TRANSACTION_DESTROY) {
            return false
        }
        exitProcess(0)
    }

    companion object {
        // The constant is library-internal, but it is the only name for Shizuku's destroy call.
        @SuppressLint("RestrictedApi")
        private const val TRANSACTION_DESTROY = ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy
    }
}
