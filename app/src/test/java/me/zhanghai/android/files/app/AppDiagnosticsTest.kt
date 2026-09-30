/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AppDiagnosticsTest {
    @Test
    fun aKillForUsingTooMuchSaysSoWithItsDescription() {
        assertEquals(
            "Earlier process me.zhanghai.android.files (pid 15309) ended at " +
                "2026-09-23T21:56:51.624Z: excessive resource usage, status 0, importance 400, " +
                "rss 232 MB, excessive binder traffic during cached state",
            formatProcessExit(
                1_790_200_611_624,
                "me.zhanghai.android.files",
                15309,
                exitReasonName(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE),
                0,
                400,
                232L * 1024,
                "excessive binder traffic during cached state"
            )
        )
    }

    @Test
    fun anExitWithoutADescriptionEndsAtItsMemory() {
        val exit = formatProcessExit(0, "p", 1, "ANR", 0, 100, 2048, null)
        assertEquals(true, exit.endsWith("rss 2 MB"))
    }

    @Test
    fun everyReasonHasAName() {
        assertEquals("ANR", exitReasonName(ApplicationExitInfo.REASON_ANR))
        assertEquals("low memory", exitReasonName(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("package updated", exitReasonName(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
        assertEquals("reason 999", exitReasonName(999))
        for (reason in 0..16) {
            assertEquals(false, exitReasonName(reason).startsWith("reason "))
        }
    }
}
