/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.net.Uri
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings written before the app parcelled Uris itself hold the platform's own parcelling, and
 * every kind of platform Uri has to read back as the same Uri.
 */
@RunWith(AndroidJUnit4::class)
class StableUriParcelerPlatformTest {
    @Test
    fun aStringUriReadsBack() {
        assertReadsBack(Uri.parse("content://authority/path?query#fragment"))
    }

    @Test
    fun anOpaqueUriReadsBack() {
        assertReadsBack(Uri.fromParts("mailto", "someone@example.com", "fragment"))
        assertReadsBack(Uri.fromParts("tel", "123", null))
    }

    @Test
    fun aHierarchicalUriReadsBack() {
        assertReadsBack(
            Uri.Builder()
                .scheme("content")
                .authority("com.android.externalstorage.documents")
                .appendPath("tree")
                .appendPath("primary:Download")
                .encodedQuery("a=1")
                .fragment("top")
                .build()
        )
        // A Uri without a scheme is not covered: the platform now writes the whole string where
        // older versions wrote just the scheme, and only the ':' tells the two apart. Nothing
        // persisted is relative.
    }

    @Test
    fun ourOwnParcellingReadsBack() {
        val uri = Uri.parse("file:///sdcard/Download")
        val parcel = Parcel.obtain()
        try {
            with(StableUriParceler) { uri.write(parcel, 0) }
            parcel.setDataPosition(0)
            assertEquals(uri, StableUriParceler.create(parcel))
        } finally {
            parcel.recycle()
        }
    }

    private fun assertReadsBack(uri: Uri) {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(uri, 0)
            parcel.setDataPosition(0)
            assertEquals(uri.toString(), StableUriParceler.create(parcel).toString())
        } finally {
            parcel.recycle()
        }
    }
}
