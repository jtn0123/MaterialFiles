/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.compat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [SELinuxCompat.native_restorecon] is a restricted hidden API, so reaching it at all also checks
 * that the hidden API exemptions the app installs at startup are in place.
 */
@RunWith(AndroidJUnit4::class)
class SELinuxCompatTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun restoreconFailsForPathsThatDoNotExist() {
        val missingFile = File(context.filesDir, "selinux-restorecon-missing")
        missingFile.delete()
        val missingDirectory = File(context.filesDir, "selinux-restorecon-missing/child")

        assertFalse(SELinuxCompat.native_restorecon(missingFile.path, 0))
        assertFalse(SELinuxCompat.native_restorecon(missingDirectory.path, RECURSE))
    }

    companion object {
        // @see selinux_android_restorecon() SELINUX_ANDROID_RESTORECON_RECURSE
        private const val RECURSE = 4
    }
}
