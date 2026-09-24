/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import java8.nio.file.Path
import java8.nio.file.ProviderMismatchException
import me.zhanghai.android.files.provider.common.requireProviderPath

fun Path.archiveAddPassword(password: String) {
    requireProviderPath<ArchivePath>(this)
    fileSystem.addPassword(password)
}

val Path.archiveFile: Path
    get() {
        requireProviderPath<ArchivePath>(this)
        return fileSystem.archiveFile
    }

fun Path.archiveRefresh() {
    requireProviderPath<ArchivePath>(this)
    fileSystem.refresh()
}

fun Path.createArchiveRootPath(): Path =
    ArchiveFileSystemProvider.getOrNewFileSystem(this).rootDirectory
