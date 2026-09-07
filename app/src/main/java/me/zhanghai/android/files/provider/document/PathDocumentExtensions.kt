/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.document

import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.IOException
import java8.nio.file.Path
import java8.nio.file.ProviderMismatchException
import me.zhanghai.android.files.provider.content.resolver.ResolverException
import me.zhanghai.android.files.provider.document.resolver.DocumentResolver

val Path.documentUri: Uri
    @Throws(IOException::class)
    get() {
        this as? DocumentPath ?: throw ProviderMismatchException(toString())
        return try {
            DocumentResolver.getDocumentUri(this)
        } catch (e: ResolverException) {
            throw e.toFileSystemException(toString())
        }
    }

val Path.documentTreeUri: Uri
    get() {
        this as? DocumentPath ?: throw ProviderMismatchException(toString())
        return treeUri
    }

fun Uri.createDocumentTreeRootPath(): Path =
    DocumentFileSystemProvider.getOrNewFileSystem(this).rootDirectory

/** Whether this document path is backed by local storage rather than a cloud provider. */
val Path.isLocalDocument: Boolean
    get() {
        this as? DocumentPath ?: throw ProviderMismatchException(toString())
        return DocumentResolver.isLocal(this)
    }

@Throws(IOException::class)
fun Path.openDocumentParcelFileDescriptor(mode: String): ParcelFileDescriptor {
    this as? DocumentPath ?: throw ProviderMismatchException(toString())
    return try {
        DocumentResolver.openParcelFileDescriptor(this, mode)
    } catch (e: ResolverException) {
        throw e.toFileSystemException(toString())
    }
}

/** The provider's own thumbnail for this document, or null when it has none. */
@Throws(IOException::class)
fun Path.getDocumentThumbnail(width: Int, height: Int, signal: CancellationSignal): Bitmap? {
    this as? DocumentPath ?: throw ProviderMismatchException(toString())
    return try {
        DocumentResolver.getThumbnail(this, width, height, signal)
    } catch (e: ResolverException) {
        throw e.toFileSystemException(toString())
    }
}
