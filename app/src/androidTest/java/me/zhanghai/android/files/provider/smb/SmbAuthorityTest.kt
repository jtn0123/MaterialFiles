/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb

import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import java8.nio.file.Paths
import me.zhanghai.android.files.provider.smb.client.Authority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmbAuthorityTest {
    private fun Authority.parcelRoundTrip(): Authority {
        val parcel = Parcel.obtain()
        try {
            writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return Authority.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun parcelRoundTripKeepsEveryField() {
        val authority = Authority("nas.local", 4450, "tester", "WORKGROUP", encrypt = true)
        assertEquals(authority, authority.parcelRoundTrip())
        val plain = Authority("nas.local", Authority.DEFAULT_PORT, "tester", null)
        assertEquals(plain, plain.parcelRoundTrip())
    }

    @Test
    fun readsServersSavedBeforeTheEncryptionOption() {
        // The layout @Parcelize produced for the four-field class.
        val parcel = Parcel.obtain()
        try {
            parcel.writeString("nas.local")
            parcel.writeInt(445)
            parcel.writeString("tester")
            parcel.writeString(null)
            parcel.setDataPosition(0)
            val authority = Authority.CREATOR.createFromParcel(parcel)
            assertEquals(Authority("nas.local", 445, "tester", null), authority)
            assertFalse(authority.encrypt)
            assertEquals("legacy parcel fully consumed", parcel.dataSize(), parcel.dataPosition())
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun encryptionSurvivesTheUriRoundTrip() {
        val authority = Authority("nas.local", Authority.DEFAULT_PORT, "tester", "HOME", true)
        val path = authority.createSmbRootPath().resolve("share/dir")
        val uri = path.toUri()
        assertEquals("smb://HOME%5Ctester@nas.local/share/dir?encrypt=true", uri.toString())
        val parsed = Paths.get(uri)
        assertEquals(path, parsed)
        assertTrue((parsed.fileSystem as SmbFileSystem).authority.encrypt)
    }

    @Test
    fun plainAuthorityKeepsTheOldUriShape() {
        val authority = Authority("nas.local", Authority.DEFAULT_PORT, "tester", null)
        val path = authority.createSmbRootPath().resolve("share")
        assertEquals("smb://tester@nas.local/share", path.toUri().toString())
        assertFalse((Paths.get(path.toUri()).fileSystem as SmbFileSystem).authority.encrypt)
    }
}
