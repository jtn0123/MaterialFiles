/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.linux

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import me.zhanghai.android.files.provider.root.isRunningAsRoot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app op behind "Install unknown apps" opens up Android/obb. The test only ever allows it:
 * losing an allowed app op makes StorageManagerService kill the process, test runner included.
 * The connected test task uninstalls the app afterwards, which resets it.
 */
@RunWith(AndroidJUnit4::class)
class RequestInstallPackagesAppOpTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val packageName = instrumentation.targetContext.packageName

    @Test
    fun theAppOpIsReadAndOnceAllowedStaysAllowed() {
        if (!RequestInstallPackagesAppOp.isAllowed()) {
            shell("appops set $packageName REQUEST_INSTALL_PACKAGES deny")
            assertFalse(RequestInstallPackagesAppOp.isAllowed())
            shell("appops set $packageName REQUEST_INSTALL_PACKAGES allow")
        }

        assertTrue(RequestInstallPackagesAppOp.isAllowed())
        assertTrue("The allowed answer is kept", RequestInstallPackagesAppOp.isAllowed())
    }

    @Test
    fun theAppIsNotRunningAsRoot() {
        assertFalse(isRunningAsRoot)
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            FileInputStream(fd.fileDescriptor).use { String(it.readBytes()) }
        }
}
