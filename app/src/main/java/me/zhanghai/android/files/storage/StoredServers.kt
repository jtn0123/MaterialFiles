/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import java8.nio.file.Path
import me.zhanghai.android.files.provider.ftp.FtpPath
import me.zhanghai.android.files.provider.sftp.SftpPath
import me.zhanghai.android.files.provider.smb.SmbPath
import me.zhanghai.android.files.provider.webdav.WebDavPath
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

/**
 * The stored server that [path] is on, whose edit screen is where its credentials are fixed, or
 * null if [path] is not on a stored server.
 */
fun findStoredServer(path: Path): Storage? {
    val authority = path.serverAuthority ?: return null
    return findStoredServer(authority, Settings.STORAGES.valueCompat)
}

/**
 * The server in [storages] that signs in as [authority]. The authorities of the different
 * protocols are different classes, so a server of one protocol never matches another's.
 */
internal fun findStoredServer(authority: Any, storages: List<Storage>): Storage? =
    storages.find { it.serverAuthority == authority }

private val Path.serverAuthority: Any?
    get() = when (this) {
        is SmbPath -> authority
        is SftpPath -> authority
        is FtpPath -> authority
        is WebDavPath -> authority
        else -> null
    }

internal val Storage.serverAuthority: Any?
    get() = when (this) {
        is SmbServer -> authority
        is SftpServer -> authority
        is FtpServer -> authority
        is WebDavServer -> authority
        else -> null
    }
