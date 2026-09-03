/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.document.resolver

import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.annotation.RequiresApi
import java.io.IOException
import java8.nio.file.NoSuchFileException
import me.zhanghai.android.files.app.contentResolver
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.content.resolver.Resolver
import me.zhanghai.android.files.provider.content.resolver.ResolverException
import me.zhanghai.android.files.provider.document.resolver.DocumentResolver.Path

@RequiresApi(Build.VERSION_CODES.N)
@Throws(ResolverException::class)
internal fun DocumentResolver.copyApi24(
    sourcePath: Path,
    targetPath: Path,
    intervalMillis: Long,
    listener: ((Long) -> Unit)?
): Uri {
    val sourceUri = getDocumentUri(sourcePath)
    val targetParentUri = getDocumentUri(targetPath.requireParent())
    val copiedTargetUri = try {
        // This doesn't support progress interval millis and interruption.
        DocumentsContract.copyDocument(contentResolver, sourceUri, targetParentUri)
    } catch (e: UnsupportedOperationException) {
        // Ignored.
        return copyManually(sourcePath, targetPath, intervalMillis, listener)
    } catch (e: Exception) {
        throw ResolverException(e)
    } ?: throw ResolverException(
        "DocumentsContract.copyDocument() with $sourceUri and $targetParentUri returned null"
    )
    val sourceDisplayName = sourcePath.displayName
    val targetDisplayName = targetPath.displayName
    if (sourceDisplayName == targetDisplayName) {
        listener?.invokeWithSize(copiedTargetUri)
        return copiedTargetUri
    }
    val renamedTargetUri = try {
        rename(copiedTargetUri, targetDisplayName!!)
    } catch (e: ResolverException) {
        try {
            remove(copiedTargetUri, targetParentUri)
        } catch (e2: ResolverException) {
            e.addSuppressed(e2)
        }
        throw e
    }
    listener?.invokeWithSize(renamedTargetUri)
    return renamedTargetUri
}

@Throws(ResolverException::class)
internal fun DocumentResolver.copyManually(
    sourcePath: Path,
    targetPath: Path,
    intervalMillis: Long,
    listener: ((Long) -> Unit)?
): Uri {
    val sourceUri = getDocumentUri(sourcePath)
    val mimeType = try {
        getMimeType(sourceUri)
    } catch (e: ResolverException) {
        e.printStackTrace()
        null
    } ?: MimeType.GENERIC.value
    if (mimeType == MimeType.DIRECTORY.value) {
        return create(targetPath, MimeType.DIRECTORY.value)
    }
    val targetUri = create(targetPath, mimeType)
    try {
        Resolver.openInputStream(sourceUri, "r").use { inputStream ->
            Resolver.openOutputStream(targetUri, "wt").use { outputStream ->
                inputStream.copyTo(outputStream, intervalMillis, listener)
            }
        }
    } catch (e: IOException) {
        val targetParentPath = targetPath.parent
        if (targetParentPath != null) {
            try {
                val targetParentUri = getDocumentUri(targetParentPath)
                remove(targetUri, targetParentUri)
            } catch (e2: ResolverException) {
                e.addSuppressed(e2)
            }
        }
        throw ResolverException(e)
    }
    return targetUri
}

@RequiresApi(Build.VERSION_CODES.N)
@Throws(ResolverException::class)
internal fun DocumentResolver.moveApi24(
    sourcePath: Path,
    targetPath: Path,
    moveOnly: Boolean,
    intervalMillis: Long,
    listener: ((Long) -> Unit)?
): Uri {
    val sourceParentUri = getDocumentUri(sourcePath.requireParent())
    val sourceUri = getDocumentUri(sourcePath)
    val targetParentUri = getDocumentUri(targetPath.requireParent())
    val movedTargetUri = try {
        // This doesn't support progress interval millis and interruption.
        DocumentsContract.moveDocument(
            contentResolver,
            sourceUri,
            sourceParentUri,
            targetParentUri
        )
    } catch (e: UnsupportedOperationException) {
        if (moveOnly) {
            throw ResolverException(e)
        }
        return moveByCopy(sourcePath, targetPath, intervalMillis, listener)
    } catch (e: Exception) {
        throw ResolverException(e)
    } ?: throw ResolverException(
        "DocumentsContract.moveDocument() with $sourceUri and $targetParentUri returned null"
    )
    val sourceDisplayName = sourcePath.displayName
    val targetDisplayName = targetPath.displayName
    if (sourceDisplayName == targetDisplayName) {
        listener?.invokeWithSize(movedTargetUri)
        return movedTargetUri
    }
    val renamedTargetUri = rename(movedTargetUri, targetDisplayName!!)
    listener?.invokeWithSize(renamedTargetUri)
    return renamedTargetUri
}

internal fun ((Long) -> Unit).invokeWithSize(uri: Uri) {
    val size = try {
        DocumentResolver.getSize(uri)
    } catch (e: ResolverException) {
        e.printStackTrace()
        return
    } ?: return
    this(size)
}

@Throws(ResolverException::class)
internal fun DocumentResolver.moveByCopy(
    sourcePath: Path,
    targetPath: Path,
    intervalMillis: Long,
    listener: ((Long) -> Unit)?
): Uri {
    val targetUri = copy(sourcePath, targetPath, intervalMillis, listener)
    try {
        val sourceUri = getDocumentUri(sourcePath)
        val sourceParentUri = getDocumentUri(sourcePath.requireParent())
        remove(sourceUri, sourceParentUri)
    } catch (e: ResolverException) {
        if (e.toFileSystemException(sourcePath.toString()) !is NoSuchFileException) {
            try {
                val targetParentUri = getDocumentUri(targetPath.requireParent())
                remove(targetUri, targetParentUri)
            } catch (e2: ResolverException) {
                e.addSuppressed(e2)
            }
        }
        throw e
    }
    return targetUri
}
