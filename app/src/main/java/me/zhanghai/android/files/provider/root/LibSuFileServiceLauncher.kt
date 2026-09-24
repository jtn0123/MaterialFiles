/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.topjohnwu.superuser.NoShellException
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import me.zhanghai.android.files.provider.remote.IRemoteFileService
import me.zhanghai.android.files.provider.remote.RemoteFileServiceInterface
import me.zhanghai.android.files.provider.remote.RemoteFileSystemException
import me.zhanghai.android.files.util.createIntent

object LibSuFileServiceLauncher {
    private val lock = Any()

    init {
        Shell.enableVerboseLogging = true
        // What the deprecated Shell.FLAG_REDIRECT_STDERR sets for us anyway.
        Shell.enableLegacyStderrRedirection = true
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setInitializers(LibSuShellInitializer::class.java)
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(TimeUnit.MILLISECONDS.toSeconds(RootFileService.TIMEOUT_MILLIS))
        )
    }

    fun isSuAvailable(): Boolean = // @see com.topjohnwu.superuser.Shell.rootAccess
        try {
            Runtime.getRuntime().exec("su --version")
            true
        } catch (e: IOException) {
            // java.io.IOException: Cannot run program "su": error=2, No such file or directory
            false
        }

    @Throws(RemoteFileSystemException::class)
    fun launchService(): IRemoteFileService {
        synchronized(lock) {
            // libsu won't call back when su isn't available.
            if (!isSuAvailable()) {
                throw RemoteFileSystemException("Root isn't available")
            }
            return try {
                runBlocking {
                    try {
                        withTimeout(RootFileService.TIMEOUT_MILLIS) {
                            // Proactively create the shell because RootService doesn't allow us to
                            // handle errors during shell creation.
                            createShell()
                            bindService()
                        }
                    } catch (e: TimeoutCancellationException) {
                        throw RemoteFileSystemException(e)
                    }
                }
            } catch (e: InterruptedException) {
                throw RemoteFileSystemException(e)
            }
        }
    }

    @Throws(RemoteFileSystemException::class)
    private suspend fun createShell() {
        suspendCancellableCoroutine<Unit> { continuation ->
            // Shell.getShell(GetShellCallback) doesn't allow handling errors.
            Shell.EXECUTOR.execute {
                try {
                    Shell.getShell()
                    continuation.resume(Unit)
                } catch (e: NoShellException) {
                    continuation.resumeWithException(RemoteFileSystemException(e))
                }
            }
        }
    }

    @Throws(RemoteFileSystemException::class)
    private suspend fun CoroutineScope.bindService(): IRemoteFileService =
        suspendCancellableCoroutine { continuation ->
            val intent = LibSuFileService::class.createIntent()
            val connection = LibSuServiceConnection(continuation)
            launch(Dispatchers.Main.immediate) {
                RootService.bind(intent, connection)
                continuation.invokeOnCancellation {
                    launch(Dispatchers.Main.immediate) { RootService.unbind(connection) }
                }
            }
        }
}

/** Resumes [continuation] with the bound service, or with why there is none. */
private class LibSuServiceConnection(
    private val continuation: CancellableContinuation<IRemoteFileService>
) : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        continuation.resume(IRemoteFileService.Stub.asInterface(service))
    }

    override fun onServiceDisconnected(name: ComponentName) {
        failIfActive("libsu service disconnected")
    }

    override fun onBindingDied(name: ComponentName) {
        failIfActive("libsu binding died")
    }

    override fun onNullBinding(name: ComponentName) {
        failIfActive("libsu binding is null")
    }

    private fun failIfActive(message: String) {
        if (continuation.isActive) {
            continuation.resumeWithException(RemoteFileSystemException(message))
        }
    }
}

private class LibSuShellInitializer : Shell.Initializer() {
    // Prevent normal shells from being created and set as the main shell.
    override fun onInit(context: Context, shell: Shell): Boolean = shell.isRoot
}

class LibSuFileService : RootService() {
    override fun onCreate() {
        super.onCreate()

        RootFileService.main()
    }

    override fun onBind(intent: Intent): IBinder = RemoteFileServiceInterface()
}
