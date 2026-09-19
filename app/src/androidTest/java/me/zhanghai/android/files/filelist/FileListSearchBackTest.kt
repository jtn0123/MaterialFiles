/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import me.zhanghai.android.files.UiFailureDiagnosticsRule
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * With predictive back enabled AppCompat no longer collapses an expanded search view on Back, so
 * the file list has to, before its own navigate-up callback gets a chance.
 *
 * Emulator only: `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class FileListSearchBackTest {
    @get:Rule
    val diagnostics = UiFailureDiagnosticsRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val directory =
        File(Environment.getExternalStorageDirectory(), "Movies/MaterialFilesSearchBackTest")
    private val subdirectory = File(directory, SUBDIRECTORY_NAME)
    private var scenario: ActivityScenario<FileListActivity>? = null

    private val searchText = By.res(context.packageName, "search_src_text")

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        subdirectory.mkdirs()
        File(directory, PARENT_FILE_NAME).writeText("parent")
        File(subdirectory, CHILD_FILE_NAME).writeText("child")
    }

    @After
    fun tearDown() {
        scenario?.close()
        directory.deleteRecursively()
    }

    @Test
    fun backClosesSearchBeforeGoingUp() {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(subdirectory), "inode/directory")
            .setClass(context, FileListActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        scenario = ActivityScenario.launch(intent)
        assertNotNull(
            "The file list never appeared",
            device.wait(Until.findObject(By.text(CHILD_FILE_NAME)), TIMEOUT_MILLIS)
        )

        checkNotNull(device.findObject(By.desc("Search"))).click()
        assertNotNull(
            "The search view never opened",
            device.wait(Until.findObject(searchText), TIMEOUT_MILLIS)
        )

        // The first Back may only hide the keyboard; the one after it must close search.
        var closed = false
        repeat(2) {
            if (!closed) {
                device.pressBack()
                closed = device.wait(Until.gone(searchText), SHORT_TIMEOUT_MILLIS)
            }
        }
        assertTrue("Back did not close the search view", closed)
        assertNotNull(
            "Back left the folder instead of only closing search",
            device.wait(Until.findObject(By.text(CHILD_FILE_NAME)), TIMEOUT_MILLIS)
        )
        assertFalse(
            "Back went up to the parent folder",
            device.hasObject(By.text(PARENT_FILE_NAME))
        )

        // With search closed, Back goes up a folder as before.
        device.pressBack()
        assertNotNull(
            "Back no longer goes up a folder once search is closed",
            device.wait(Until.findObject(By.text(PARENT_FILE_NAME)), TIMEOUT_MILLIS)
        )
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
    }

    companion object {
        private const val SUBDIRECTORY_NAME = "inner"
        private const val PARENT_FILE_NAME = "parent-file.txt"
        private const val CHILD_FILE_NAME = "child-file.txt"
        private const val TIMEOUT_MILLIS = 30_000L
        private const val SHORT_TIMEOUT_MILLIS = 3_000L
    }
}
