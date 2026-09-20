/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.file

import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.zhanghai.android.files.compat.DocumentsContractCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** What the app makes of the content URIs that the storage access framework hands out. */
@RunWith(AndroidJUnit4::class)
class DocumentUriTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private val primaryTreeUri: Uri
        get() = DocumentsContract.buildTreeDocumentUri(
            DocumentsContractCompat.EXTERNAL_STORAGE_PROVIDER_AUTHORITY,
            "${DocumentsContractCompat.EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID}:"
        )

    @Test
    fun aTreeIsToldApartFromADocumentAndFromAnythingElse() {
        val tree = primaryTreeUri.asDocumentTreeUri()
        assertEquals("primary:", tree.documentId)
        assertNull(primaryTreeUri.asDocumentUriOrNull())

        val document = tree.buildDocumentUri("primary:Download")
        assertEquals("primary:Download", document.documentId)
        assertEquals("primary:", document.treeDocumentId)
        assertNotNull(document.value.asDocumentUriOrNull())

        val notADocument = Uri.fromFile(context.cacheDir)
        assertNull(notADocument.asDocumentUriOrNull())
        assertNull(notADocument.asDocumentTreeUriOrNull())
    }

    @Test
    fun aDocumentFromNowhereHasNoName() {
        val document = DocumentsContract
            .buildDocumentUri("me.zhanghai.android.files.test.nothing", "nothing")
            .asDocumentUri()

        assertNull(document.displayName)
    }

    @Test
    fun aPermissionThatWasNeverGrantedIsNotHeldAndCannotBeGivenUp() {
        val tree = primaryTreeUri.asDocumentTreeUri()

        // Only what the user picked in the storage access framework can be kept.
        assertFalse(tree.takePersistablePermission())
        assertFalse(tree.releasePersistablePermission())
        assertFalse(DocumentTreeUri.persistedUris.contains(tree))
    }

    @Test
    fun theStorageVolumeAndItsTreeFindEachOther() {
        val storageManager = context.getSystemService<StorageManager>()!!
        val volume = storageManager.primaryStorageVolume

        val tree = volume.documentTreeUri

        assertEquals(primaryTreeUri, tree.value)
        assertEquals(volume.uuid, tree.storageVolume?.uuid)
    }
}
