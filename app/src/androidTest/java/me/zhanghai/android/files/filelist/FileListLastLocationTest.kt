/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Intent
import android.os.Environment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import me.zhanghai.android.files.settings.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Where the file list opens: where it was left off, or where the system destroyed it. */
@RunWith(AndroidJUnit4::class)
class FileListLastLocationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var directory: File
    private lateinit var directoryName: String
    private var previousRemember: Boolean? = null
    private var previousLocation: FileListLastLocation? = null

    @Before
    fun setUp() {
        executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        executeShellCommand(
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
        )
        // A digit sorts it to the top of the external storage listing, where it is visible.
        directoryName = "0LastLocation-${UUID.randomUUID()}"
        @Suppress("DEPRECATION")
        directory = File(Environment.getExternalStorageDirectory(), directoryName)
            .apply { mkdirs() }
        File(directory, "Inside.txt").writeText("Inside the remembered folder")
        instrumentation.runOnMainSync {
            previousRemember = Settings.FILE_LIST_REMEMBER_LAST_DIRECTORY.value
            previousLocation = Settings.FILE_LIST_LAST_LOCATION.value
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            previousRemember?.let { Settings.FILE_LIST_REMEMBER_LAST_DIRECTORY.putValue(it) }
            Settings.FILE_LIST_LAST_LOCATION.putValue(previousLocation)
        }
        directory.deleteRecursively()
    }

    private fun executeShellCommand(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun launchFromLauncher(): ActivityScenario<FileListActivity> {
        val intent = Intent(context, FileListActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return ActivityScenario.launch(intent)
    }

    private fun openTestDirectory(scenario: ActivityScenario<FileListActivity>) {
        val folder = device.wait(Until.findObject(By.text(directoryName)), 20_000)
        assertNotNull("The test folder never appeared", folder)
        folder.click()
        assertNotNull(device.wait(Until.findObject(By.text("Inside.txt")), 20_000))
        assertEquals(directory.path, scenario.currentPath())
    }

    private fun ActivityScenario<FileListActivity>.currentPath(): String {
        var path = ""
        onActivity { activity ->
            val fragment = activity.supportFragmentManager.fragments
                .filterIsInstance<FileListFragment>().single()
            path = fragment.viewModel.currentPath.toString()
        }
        return path
    }

    @Test
    fun aPlainStartReopensWhereTheUserLeftOff() {
        instrumentation.runOnMainSync {
            Settings.FILE_LIST_REMEMBER_LAST_DIRECTORY.putValue(true)
            Settings.FILE_LIST_LAST_LOCATION.putValue(null)
        }
        launchFromLauncher().use { scenario ->
            openTestDirectory(scenario)
        }

        launchFromLauncher().use { scenario ->
            assertNotNull(device.wait(Until.findObject(By.text("Inside.txt")), 20_000))
            assertEquals(directory.path, scenario.currentPath())
        }
    }

    @Test
    fun theLastLocationIsNotRememberedWhenTheUserTurnedThatOff() {
        instrumentation.runOnMainSync {
            Settings.FILE_LIST_REMEMBER_LAST_DIRECTORY.putValue(false)
            Settings.FILE_LIST_LAST_LOCATION.putValue(null)
        }
        launchFromLauncher().use { scenario ->
            openTestDirectory(scenario)
        }

        instrumentation.runOnMainSync {
            assertEquals(null, Settings.FILE_LIST_LAST_LOCATION.value)
        }
        launchFromLauncher().use { scenario ->
            assertNotNull(device.wait(Until.findObject(By.text(directoryName)), 20_000))
            @Suppress("DEPRECATION")
            assertEquals(
                Environment.getExternalStorageDirectory().path,
                scenario.currentPath()
            )
        }
    }

    @Test
    fun theSystemRecreatingTheListKeepsItWhereItWas() {
        instrumentation.runOnMainSync {
            Settings.FILE_LIST_REMEMBER_LAST_DIRECTORY.putValue(false)
            Settings.FILE_LIST_LAST_LOCATION.putValue(null)
        }
        launchFromLauncher().use { scenario ->
            openTestDirectory(scenario)

            scenario.recreate()

            assertNotNull(device.wait(Until.findObject(By.text("Inside.txt")), 20_000))
            assertEquals(directory.path, scenario.currentPath())
        }
    }
}
