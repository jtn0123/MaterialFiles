/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.document.resolver

import android.database.Cursor
import android.database.MergeCursor
import android.net.Uri
import android.provider.DocumentsContract
import me.zhanghai.android.files.compat.DocumentsContractCompat
import me.zhanghai.android.files.provider.content.resolver.requireString

// On Android 11, ExternalStorageProvider no longer returns Android/data and Android/obb as children
// of the Android directory on primary storage. However, the two child directories are actually
// still accessible.
object ExternalStorageProviderHacks {
    private const val DOCUMENT_ID_PRIMARY = "primary"
    private const val DOCUMENT_ID_PRIMARY_ANDROID = "primary:Android"
    private const val DOCUMENT_ID_PRIMARY_ANDROID_DATA = "primary:Android/data"
    private const val DOCUMENT_ID_PRIMARY_ANDROID_OBB = "primary:Android/obb"

    private val TREE_URI_PRIMARY_ANDROID = DocumentsContract.buildTreeDocumentUri(
        DocumentsContractCompat.EXTERNAL_STORAGE_PROVIDER_AUTHORITY,
        DOCUMENT_ID_PRIMARY
    )
    val DOCUMENT_URI_ANDROID_DATA = DocumentsContract.buildDocumentUriUsingTree(
        TREE_URI_PRIMARY_ANDROID,
        DOCUMENT_ID_PRIMARY_ANDROID_DATA
    )
    val DOCUMENT_URI_ANDROID_OBB = DocumentsContract.buildDocumentUriUsingTree(
        TREE_URI_PRIMARY_ANDROID,
        DOCUMENT_ID_PRIMARY_ANDROID_OBB
    )

    fun transformQueryResult(uri: Uri, cursor: Cursor): Cursor {
        if (!isPrimaryAndroidChildrenUri(uri)) {
            return cursor
        }
        val missingDocumentIds = findMissingDocumentIds(cursor)
        if (missingDocumentIds.isEmpty()) {
            return cursor
        }
        val cursors = mutableListOf(cursor)
        for (documentId in missingDocumentIds) {
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
            cursors += DocumentResolver.query(documentUri, null, null)
        }
        return MergeCursor(cursors.toTypedArray())
    }

    private fun isPrimaryAndroidChildrenUri(uri: Uri): Boolean =
        uri.authority == DocumentsContractCompat.EXTERNAL_STORAGE_PROVIDER_AUTHORITY &&
            DocumentsContractCompat.isChildDocumentsUri(uri) &&
            DocumentsContract.getDocumentId(uri) == DOCUMENT_ID_PRIMARY_ANDROID

    // Returns which of Android/data and Android/obb the children are missing, data first, and
    // leaves the cursor before its first row.
    private fun findMissingDocumentIds(cursor: Cursor): List<String> {
        val missingDocumentIds =
            mutableListOf(DOCUMENT_ID_PRIMARY_ANDROID_DATA, DOCUMENT_ID_PRIMARY_ANDROID_OBB)
        try {
            while (missingDocumentIds.isNotEmpty() && cursor.moveToNext()) {
                missingDocumentIds -= cursor.requireString(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            }
        } finally {
            cursor.moveToPosition(-1)
        }
        return missingDocumentIds
    }
}
