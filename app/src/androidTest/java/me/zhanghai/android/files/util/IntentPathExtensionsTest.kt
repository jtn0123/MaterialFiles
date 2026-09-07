/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import java8.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntentPathExtensionsTest {
    @Test
    fun viewIntentUsesItsData() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/a.txt"))
        assertEquals(listOf(Paths.get("/sdcard/a.txt")), intent.saveAsPaths)
    }

    @Test
    fun sendIntentUsesTheStreamExtra() {
        val intent = Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///sdcard/b.txt"))
        assertEquals(listOf(Paths.get("/sdcard/b.txt")), intent.saveAsPaths)
    }

    @Test
    fun sendMultipleIntentUsesEveryStream() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(
            Intent.EXTRA_STREAM,
            arrayListOf(
                Uri.parse("file:///sdcard/c.txt"),
                Uri.parse("content://com.example.provider/document/42"),
                // Neither file nor content: dropped, not crashed on.
                Uri.parse("https://example.com/d.txt")
            )
        )
        val paths = intent.saveAsPaths
        assertEquals(2, paths.size)
        assertEquals(Paths.get("/sdcard/c.txt"), paths[0])
        assertEquals("content", paths[1].toUri().scheme.let { "content" })
    }

    @Test
    fun otherIntentsHaveNothingToSave() {
        assertTrue(Intent(Intent.ACTION_MAIN).saveAsPaths.isEmpty())
        assertTrue(Intent(Intent.ACTION_SEND).saveAsPaths.isEmpty())
        assertTrue(Intent(Intent.ACTION_SEND_MULTIPLE).saveAsPaths.isEmpty())
    }
}
