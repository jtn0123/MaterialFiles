/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.apk

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo

class ApkInfo(
    val packageInfo: PackageInfo,
    // PackageInfo.applicationInfo is nullable; an APK without one fails to load instead.
    val applicationInfo: ApplicationInfo,
    val label: String,
    val signingCertificateDigests: List<String>,
    val pastSigningCertificateDigests: List<String>
)
