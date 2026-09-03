/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.net.Uri
import android.os.Build
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.util.createInstallPackageIntent

class InstallApkJob(private val file: Path) : FileJob() {
    override fun run() {
        open(
            file,
            R.string.file_install_apk_from_background_title_format,
            R.string.file_install_apk_from_background_text
        ) { file ->
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                file.fileProviderUri
            } else {
                // PackageInstaller only supports file URI before N.
                Uri.fromFile(file.toFile())
            }
            uri.createInstallPackageIntent()
        }
    }
}
