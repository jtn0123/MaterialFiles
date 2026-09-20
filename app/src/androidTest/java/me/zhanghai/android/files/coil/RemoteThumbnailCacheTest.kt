/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What the thumbnail cache keeps on disk, and what it refuses to keep. */
@RunWith(AndroidJUnit4::class)
class RemoteThumbnailCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun newKey(): String = "thumbnail-test-${UUID.randomUUID()}"

    @Test
    fun aCachedThumbnailComesBackAsAnImageOfTheSameSize() {
        val key = newKey()
        val bitmap = createBitmap(40, 30).applyCanvas { drawColor(Color.RED) }

        RemoteThumbnails.put(key, BitmapDrawable(context.resources, bitmap))

        assertTrue(RemoteThumbnails.contains(key))
        val result = checkNotNull(RemoteThumbnails.get(key))
        assertEquals("image/webp", result.mimeType)
        val cached = result.source.use { source ->
            source.source().inputStream().use { BitmapFactory.decodeStream(it) }
        }
        assertNotNull(cached)
        assertEquals(40, cached!!.width)
        assertEquals(30, cached.height)
        // The thumbnail is kept as lossy WebP, so the red it was drawn in comes back close enough.
        val pixel = cached.getPixel(20, 15)
        assertTrue(
            "#%08x".format(pixel),
            Color.red(pixel) > 200 && Color.green(pixel) < 64 && Color.blue(pixel) < 64
        )
    }

    @Test
    fun aDrawableWithoutASizeIsNotWorthCaching() {
        val key = newKey()

        RemoteThumbnails.put(key, ColorDrawable(Color.BLUE))

        assertFalse(RemoteThumbnails.contains(key))
        assertNull(RemoteThumbnails.get(key))
    }

    @Test
    fun aKeyMadeOfAWholePathIsStillCached() {
        // Keys are made of paths, which hold spaces, upper case and anything else a file name may
        // have, while the cache on disk only takes lower case names.
        val key = "smb://Server/Photos/Holiday 2026/DSC_0001.JPG:1024x1024"

        RemoteThumbnails.put(key, BitmapDrawable(context.resources, createBitmap(8, 8)))

        assertTrue(RemoteThumbnails.contains(key))
        val result = checkNotNull(RemoteThumbnails.get(key))
        val cached = result.source.use { source ->
            source.source().inputStream().use { BitmapFactory.decodeStream(it) }
        }
        assertEquals(8, cached!!.width)
    }
}
