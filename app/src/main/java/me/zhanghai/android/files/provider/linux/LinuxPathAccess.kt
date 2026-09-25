/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.linux

import android.app.AppOpsManager
import android.os.Process
import java.io.File
import kotlin.concurrent.Volatile
import me.zhanghai.android.files.app.appOpsManager
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.compat.AppOpsManagerCompat
import me.zhanghai.android.files.compat.checkOpRawNoThrowCompat

private val FILE_ANDROID_DATA = File("Android/data")
private val FILE_ANDROID_OBB = File("Android/obb")

/**
 * Whether this app can reach this file in a storage volume without root. Other apps' directories
 * in Android/data are off limits, and so are those in Android/obb unless [isObbAccessAllowed].
 * For attribute access the file's own entry is what matters, which lives in its parent directory.
 */
internal fun File.isAccessibleInStorageVolume(
    storageVolumeDirectory: File,
    isAttributeAccess: Boolean,
    packageName: String,
    isObbAccessAllowed: () -> Boolean
): Boolean {
    val androidDataDirectory = storageVolumeDirectory.resolve(FILE_ANDROID_DATA)
    if (isInDirectory(androidDataDirectory, isAttributeAccess)) {
        return startsWith(androidDataDirectory.resolve(packageName))
    }
    val androidObbDirectory = storageVolumeDirectory.resolve(FILE_ANDROID_OBB)
    if (isInDirectory(androidObbDirectory, isAttributeAccess)) {
        // Note that StorageManagerService won't automatically kill and restart our process when we
        // are granted REQUEST_INSTALL_PACKAGES for us to get access to Android/obb immediately
        // since S, similar to it not automatically killing and restarting our process when we are
        // granted MANAGE_EXTERNAL_STORAGE for us to get access to external storage volumes
        // immediately. But we aren't handling the latter anyway, so let's not handle the former
        // here either.
        return isObbAccessAllowed() || startsWith(androidObbDirectory.resolve(packageName))
    }
    return true
}

private fun File.isInDirectory(directory: File, isAttributeAccess: Boolean): Boolean {
    val parentDirectory = parentFile
    return if (isAttributeAccess && parentDirectory != null) {
        parentDirectory.startsWith(directory)
    } else {
        startsWith(directory)
    }
}

/** Whether the REQUEST_INSTALL_PACKAGES app op, which opens up Android/obb, is allowed. */
internal object RequestInstallPackagesAppOp {
    // IPC for checking the app op is expensive, and we'll be killed by StorageManagerService when
    // losing the app op, so let's just cache the result if it was ever allowed.
    @Volatile
    private var wasAllowed = false

    fun isAllowed(): Boolean {
        if (wasAllowed) {
            return true
        }
        // We'll never have the signature|appop permission itself, so we only need to check the app
        // op against MODE_ALLOWED.
        val mode = appOpsManager.checkOpRawNoThrowCompat(
            AppOpsManagerCompat.OPSTR_REQUEST_INSTALL_PACKAGES,
            Process.myUid(),
            application.opPackageName,
            null
        )
        return (mode == AppOpsManager.MODE_ALLOWED).also { wasAllowed = it }
    }
}
