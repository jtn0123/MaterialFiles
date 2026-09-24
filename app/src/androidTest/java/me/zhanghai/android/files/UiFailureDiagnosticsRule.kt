/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Saves a screenshot and the window hierarchy when a UI test fails, so that a "never appeared"
 * assertion on a CI emulator can be diagnosed from the uploaded artifacts.
 *
 * Files go to the directory the Android Gradle plugin passes as `additionalTestOutputDir`, which
 * `connectedAndroidTest` pulls into
 * `app/build/outputs/connected_android_test_additional_output/`. When the argument is absent
 * (for example under a plain `am instrument`), nothing is written.
 */
class UiFailureDiagnosticsRule : TestWatcher() {
    private var testName: String? = null

    override fun starting(description: Description) {
        testName = "${description.className}-${description.methodName}"
    }

    override fun failed(e: Throwable?, description: Description) {
        save("${description.className}-${description.methodName}")
    }

    /**
     * Saves the screen as it is now; for a test that closes its activity before failing, which
     * would leave [failed] only the launcher to capture.
     */
    fun capture(label: String) {
        save("$testName-$label")
    }

    private fun save(baseName: String) {
        val directory = outputDirectory ?: return
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            device.takeScreenshot(File(directory, "$baseName.png"))
            File(directory, "$baseName.xml").outputStream().use {
                device.dumpWindowHierarchy(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save UI diagnostics for $baseName", e)
        }
    }

    private val outputDirectory: File?
        get() {
            val path = InstrumentationRegistry.getArguments()
                .getString(ARGUMENT_ADDITIONAL_TEST_OUTPUT_DIR) ?: return null
            return File(path).takeIf { it.isDirectory || it.mkdirs() }
        }

    companion object {
        private const val TAG = "UiFailureDiagnostics"
        private const val ARGUMENT_ADDITIONAL_TEST_OUTPUT_DIR = "additionalTestOutputDir"
    }
}
