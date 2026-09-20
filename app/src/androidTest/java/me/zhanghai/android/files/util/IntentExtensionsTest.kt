/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import android.provider.MediaStore
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.zhanghai.android.files.file.MimeType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The intents the app hands to other apps, and the extras it reads back from theirs. */
@RunWith(AndroidJUnit4::class)
class IntentExtensionsTest {
    private val uri = Uri.parse("content://me.zhanghai.android.files.test/1")

    @Test
    fun pickingAnImageOffersCapturingOneToo() {
        val intent = Intent::class.createPickOrCaptureImageWithChooser(captureOutputUri = uri)

        assertEquals(Intent.ACTION_CHOOSER, intent.action)
        val pick = intent.getParcelableExtraSafe<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, pick.action)
        assertEquals(MimeType.IMAGE_ANY.value, pick.type)
        assertTrue(pick.hasCategory(Intent.CATEGORY_OPENABLE))
        assertFalse(pick.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        val initialIntents = intent.getParcelableArrayExtraSafe(Intent.EXTRA_INITIAL_INTENTS)!!
        assertEquals(1, initialIntents.size)
        val capture = initialIntents[0] as Intent
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, capture.action)
        assertEquals(uri, capture.getParcelableExtraSafe<Uri>(MediaStore.EXTRA_OUTPUT))
    }

    @Test
    fun pickingSeveralImagesSaysSo() {
        val intent = Intent::class.createPickImage(true)

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test
    fun syncSettingsOnlyCarriesTheFiltersItWasGiven() {
        val all = Intent::class.createSyncSettings()

        assertEquals(Settings.ACTION_SYNC_SETTINGS, all.action)
        assertNull(all.getStringArrayExtra(Settings.EXTRA_AUTHORITIES))
        assertNull(all.getStringArrayExtra(Settings.EXTRA_ACCOUNT_TYPES))

        val filtered = Intent::class.createSyncSettings(
            arrayOf("authority"),
            arrayOf("accountType")
        )

        assertArrayEquals(
            arrayOf("authority"),
            filtered.getStringArrayExtra(Settings.EXTRA_AUTHORITIES)
        )
        assertArrayEquals(
            arrayOf("accountType"),
            filtered.getStringArrayExtra(Settings.EXTRA_ACCOUNT_TYPES)
        )

        val empty = Intent::class.createSyncSettings(emptyArray(), emptyArray())

        assertNull(empty.getStringArrayExtra(Settings.EXTRA_AUTHORITIES))
        assertNull(empty.getStringArrayExtra(Settings.EXTRA_ACCOUNT_TYPES))
    }

    @Test
    fun installingAPackageAsksForReadAccessToIt() {
        val intent = uri.createInstallPackageIntent()

        assertEquals(Intent.ACTION_INSTALL_PACKAGE, intent.action)
        assertEquals(uri, intent.data)
        assertEquals(MimeType.APK.value, intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun capturingAnImageSaysWhereToPutIt() {
        val intent = uri.createCaptureImage()

        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, intent.action)
        assertEquals(uri, intent.getParcelableExtraSafe<Uri>(MediaStore.EXTRA_OUTPUT))
    }

    /** The parcelable readers put the app's class loader back before reading the extras. */
    @Test
    fun parcelableListsSurviveAParcelRoundTrip() {
        val intent = Intent().putParcelableArrayListExtra(KEY, arrayListOf(uri))

        val restored = intent.parcelRoundTrip().getParcelableArrayListExtraSafe<Uri>(KEY)!!

        assertEquals(listOf(uri), restored)
    }

    @Test
    fun parcelableArraysSurviveAParcelRoundTrip() {
        val intent = Intent().putExtra(KEY, arrayOf<Parcelable>(uri))

        val restored = intent.parcelRoundTrip().getParcelableArrayExtraSafe(KEY)!!

        assertEquals(listOf<Parcelable>(uri), restored.toList())
    }

    private fun Intent.parcelRoundTrip(): Intent {
        val parcel = android.os.Parcel.obtain()
        return try {
            parcel.writeParcelable(this, 0)
            parcel.setDataPosition(0)
            parcel.readParcelable(null, Intent::class.java)!!
        } finally {
            parcel.recycle()
        }
    }

    companion object {
        private const val KEY = "key"
    }
}
