/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.compat

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The URI predicates that decide how a document URI is handled. */
@RunWith(AndroidJUnit4::class)
class DocumentsContractCompatTest {
    @Test
    fun onlyContentUrisWithADocumentPathAreDocumentUris() {
        assertTrue(DocumentsContractCompat.isDocumentUri(Uri.parse("$AUTHORITY/document/primary")))
        assertTrue(
            DocumentsContractCompat.isDocumentUri(
                Uri.parse("$AUTHORITY/tree/primary/document/primary%3ADownload")
            )
        )
        assertFalse(DocumentsContractCompat.isDocumentUri(Uri.parse("$AUTHORITY/tree/primary")))
        assertFalse(DocumentsContractCompat.isDocumentUri(Uri.parse("$AUTHORITY/document")))
        assertFalse(
            DocumentsContractCompat.isDocumentUri(Uri.parse("file:///storage/emulated/0/a.txt"))
        )
    }

    @Test
    fun onlyTreeUrisAreTreeUris() {
        assertTrue(DocumentsContractCompat.isTreeUri(Uri.parse("$AUTHORITY/tree/primary")))
        assertTrue(
            DocumentsContractCompat.isTreeUri(
                Uri.parse("$AUTHORITY/tree/primary/document/primary%3ADownload")
            )
        )
        assertFalse(DocumentsContractCompat.isTreeUri(Uri.parse("$AUTHORITY/document/primary")))
    }

    @Test
    fun onlyChildDocumentsUrisAreChildDocumentsUris() {
        assertTrue(
            DocumentsContractCompat.isChildDocumentsUri(
                Uri.parse("$AUTHORITY/document/primary/children")
            )
        )
        assertTrue(
            DocumentsContractCompat.isChildDocumentsUri(
                Uri.parse("$AUTHORITY/tree/primary/document/primary/children")
            )
        )
        assertFalse(
            DocumentsContractCompat.isChildDocumentsUri(Uri.parse("$AUTHORITY/document/primary"))
        )
        assertFalse(
            DocumentsContractCompat.isChildDocumentsUri(
                Uri.parse("$AUTHORITY/tree/primary/document/primary")
            )
        )
    }

    @Test
    fun theDocumentsUiPackageHoldsTheManageDocumentsPermission() {
        val packageName = DocumentsContractCompat.getDocumentsUiPackage()

        // Every emulator image with the Play Store has DocumentsUI installed.
        assertEquals("com.google.android.documentsui", packageName)
    }

    companion object {
        private const val AUTHORITY =
            "content://${DocumentsContractCompat.EXTERNAL_STORAGE_PROVIDER_AUTHORITY}"
    }
}
