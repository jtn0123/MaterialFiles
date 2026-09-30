/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.compat

import android.app.AppOpsManager

object AppOpsManagerCompat {
    val OPSTR_REQUEST_INSTALL_PACKAGES =
        checkNotNull(
            AppOpsManager.permissionToOp(android.Manifest.permission.REQUEST_INSTALL_PACKAGES)
        )
}
