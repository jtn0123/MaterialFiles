/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.document.resolver

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.zhanghai.android.files.compat.DocumentsContractCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Since Android 11 the platform's storage provider leaves Android/data and Android/obb out of the
 * listing of the Android directory, even though both are still there, so the listing is patched
 * back up. Everything else has to be handed through untouched.
 */
@RunWith(AndroidJUnit4::class)
class ExternalStorageProviderHacksTest {
    private val primaryTreeUri: Uri = DocumentsContract.buildTreeDocumentUri(
        DocumentsContractCompat.EXTERNAL_STORAGE_PROVIDER_AUTHORITY,
        "primary"
    )

    @Test
    fun theAndroidDataAndObbUrisPointAtThePrimaryStorageTree() {
        assertEquals(
            "primary:Android/data",
            DocumentsContract.getDocumentId(ExternalStorageProviderHacks.DOCUMENT_URI_ANDROID_DATA)
        )
        assertEquals(
            "primary:Android/obb",
            DocumentsContract.getDocumentId(ExternalStorageProviderHacks.DOCUMENT_URI_ANDROID_OBB)
        )
        assertEquals(
            DocumentsContractCompat.EXTERNAL_STORAGE_PROVIDER_AUTHORITY,
            ExternalStorageProviderHacks.DOCUMENT_URI_ANDROID_DATA.authority
        )
    }

    @Test
    fun aListingOfSomethingElseIsHandedBackUntouched() {
        val otherAuthority = DocumentsContract.buildChildDocumentsUriUsingTree(
            DocumentsContract.buildTreeDocumentUri("other.authority", "primary"),
            "primary:Android"
        )
        val otherDocument = DocumentsContract.buildChildDocumentsUriUsingTree(
            primaryTreeUri,
            "primary:Pictures"
        )
        val singleDocument = DocumentsContract.buildDocumentUriUsingTree(
            primaryTreeUri,
            "primary:Android"
        )

        for (uri in listOf(otherAuthority, otherDocument, singleDocument)) {
            val cursor = cursorOf()

            assertSame(
                "$uri was transformed",
                cursor,
                ExternalStorageProviderHacks.transformQueryResult(uri, cursor)
            )
        }
    }

    @Test
    fun aListingThatAlreadyHasBothIsHandedBackUntouchedAndRewound() {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(
            primaryTreeUri,
            "primary:Android"
        )
        val cursor =
            cursorOf("primary:Android/media", "primary:Android/data", "primary:Android/obb")

        val result = ExternalStorageProviderHacks.transformQueryResult(uri, cursor)

        assertSame(cursor, result)
        // The rows were read to look for the two, so they have to be there again for the caller.
        assertEquals(-1, result.position)
        assertEquals(3, result.count)
        result.moveToFirst()
        assertEquals(
            "primary:Android/media",
            result.getString(
                result.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            )
        )
    }

    private fun cursorOf(vararg documentIds: String): Cursor =
        MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)).apply {
            documentIds.forEach { addRow(arrayOf<Any?>(it)) }
        }
}
