/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.apk

import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import java.io.IOException
import java8.nio.file.Path
import me.zhanghai.android.files.app.packageManager
import me.zhanghai.android.files.fileproperties.PathObserverLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.backgroundExecutor
import me.zhanghai.android.files.util.getPackageArchiveInfoCompat
import me.zhanghai.android.files.util.sha1Digest
import me.zhanghai.android.files.util.toHexString
import me.zhanghai.android.files.util.valueCompat

class ApkInfoLiveData(path: Path) : PathObserverLiveData<Stateful<ApkInfo>>(path) {
    init {
        loadValue()
        observe()
    }

    override fun loadValue() {
        value = Loading(value?.value)
        backgroundExecutor.execute {
            val value = try {
                Success(loadApkInfo())
            } catch (e: Exception) {
                Failure(valueCompat.value, e)
            }
            postValue(value)
        }
    }

    private fun loadApkInfo(): ApkInfo {
        // We must always pass in PackageManager.GET_SIGNATURES for
        // PackageManager.getPackageArchiveInfo() to call
        // PackageParser.collectCertificates().
        @Suppress("DEPRECATION")
        val packageInfoFlags = (
            PackageManager.GET_PERMISSIONS
                or PackageManager.GET_SIGNATURES
                or PackageManager.GET_SIGNING_CERTIFICATES
            )
        val (packageInfo, closeable) =
            packageManager.getPackageArchiveInfoCompat(path, packageInfoFlags)
        return closeable.use {
            val applicationInfo = packageInfo?.applicationInfo
                ?: throw IOException("ApplicationInfo is null")
            val label = applicationInfo.loadLabel(packageManager).toString()
            val signingInfo = packageInfo.signingInfo
            // PackageInfo.signatures returns only the oldest certificate if there are past
            // certificates on P and above for compatibility.
            val signingCertificates = signingInfo?.apkContentsSigners ?: emptyArray()
            ApkInfo(
                packageInfo,
                label,
                signingCertificates.map { it.toSha1HexString() },
                getPastSigningCertificates(signingInfo, signingCertificates)
                    .map { it.toSha1HexString() }
            )
        }
    }

    private fun getPastSigningCertificates(
        signingInfo: SigningInfo?,
        signingCertificates: Array<Signature>
    ): List<Signature> =
        // SigningInfo.getSigningCertificateHistory() may return the current certificate if there
        // are no past certificates.
        if (signingInfo?.hasPastSigningCertificates() == true) {
            // SigningInfo.getSigningCertificateHistory() also returns the current certificate.
            signingInfo.signingCertificateHistory?.toMutableList()
                ?.apply { removeAll(signingCertificates) }
        } else {
            null
        } ?: emptyList()

    private fun Signature.toSha1HexString(): String = toByteArray().sha1Digest().toHexString()
}
