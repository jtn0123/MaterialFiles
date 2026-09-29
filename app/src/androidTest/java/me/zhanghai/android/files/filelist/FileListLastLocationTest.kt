/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Intent
import android.os.Environment
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import me.zhanghai.android.files.UiFailureDiagnosticsRule
import me.zhanghai.android.files.settings.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Where the file list opens: where it was left off, or where the system destroyed it. */
@RunWith(AndroidJUnit4::class)
class FileListLastLocationTest {
    @get:Rule
    val diagnostics = UiFailureDiagnosticsRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var directory: File
    private lateinit var directoryName: String
    private var previousRemember: Boolean? = null
    private var previousLocation: FileListLastLocation? = null
    private var previousViewType: FileViewType? = null
    private var previousSortOptions: FileSortOptions? = null

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
            previousViewType = Settings.FILE_LIST_VIEW_TYPE.value
            previousSortOptions = Settings.FILE_LIST_SORT_OPTIONS.value
            // Another test may have left a grid sorted by something else, which would put the
            // test folder out of sight.
            Settings.FILE_LIST_VIEW_TYPE.putValue(FileViewType.LIST)
            Settings.FILE_LIST_SORT_OPTIONS.putValue(
                FileSortOptions(FileSortOptions.By.NAME, FileSortOptions.Order.ASCENDING, true)
            )
        }
        // A window another test left can still be on screen, and would answer for this test's.
        awaitNoAppWindow()
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            previousRemember?.let { Settings.FILE_LIST_REMEMBER_LAST_DIRECTORY.putValue(it) }
            Settings.FILE_LIST_LAST_LOCATION.putValue(previousLocation)
            previousViewType?.let { Settings.FILE_LIST_VIEW_TYPE.putValue(it) }
            previousSortOptions?.let { Settings.FILE_LIST_SORT_OPTIONS.putValue(it) }
        }
        directory.deleteRecursively()
    }

    private fun executeShellCommand(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun awaitNoAppWindow() {
        device.wait(Until.gone(By.pkg(context.packageName)), WINDOW_GONE_TIMEOUT_MILLIS)
    }

    /** Runs [block] on a fresh start from the launcher, and waits for its window to go after. */
    private fun withLaunchFromLauncher(block: (ActivityScenario<FileListActivity>) -> Unit) {
        launchFromLauncher().use(block)
        awaitNoAppWindow()
    }

    private fun launchFromLauncher(): ActivityScenario<FileListActivity> {
        val intent = Intent(context, FileListActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // A task another test left behind can still be finishing, which drops this start back to
        // the launcher; start again instead of waiting out the search on an empty screen.
        repeat(LAUNCH_ATTEMPTS - 1) {
            val scenario = ActivityScenario.launch<FileListActivity>(intent)
            if (scenario.awaitResumed() &&
                device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 10_000)
            ) {
                return scenario
            }
            scenario.close()
            awaitNoAppWindow()
        }
        return ActivityScenario.launch(intent)
    }

    private fun ActivityScenario<FileListActivity>.awaitResumed(): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (state != Lifecycle.State.RESUMED) {
            if (System.nanoTime() >= deadline) {
                return false
            }
            Thread.sleep(100)
        }
        return true
    }

    private fun openTestDirectory(scenario: ActivityScenario<FileListActivity>) {
        val folder = device.wait(Until.findObject(By.text(directoryName)), 20_000)
        if (folder == null) {
            diagnostics.capture("noTestFolder")
        }
        assertNotNull("The test folder never appeared", folder)
        folder.click()
        assertNotNull(device.wait(Until.findObject(By.text("Inside.txt")), 20_000))
        scenario.assertPathBecomes(directory.path)
    }

    /** The path is set as the fragment gets to it, which can be just after its list shows. */
    private fun ActivityScenario<FileListActivity>.assertPathBecomes(expected: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var path = currentPath()
        while (path != expected && System.nanoTime() < deadline) {
            Thread.sleep(100)
            path = currentPath()
        }
        assertEquals(expected, path)
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
        withLaunchFromLauncher { scenario ->
            openTestDirectory(scenario)
        }

        withLaunchFromLauncher { scenario ->
            assertNotNull(device.wait(Until.findObject(By.text("Inside.txt")), 20_000))
            scenario.assertPathBecomes(directory.path)
        }
    }

    @Test
    fun theLastLocationIsNotRememberedWhenTheUserTurnedThatOff() {
        instrumentation.runOnMainSync {
            Settings.FILE_LIST_REMEMBER_LAST_DIRECTORY.putValue(false)
            Settings.FILE_LIST_LAST_LOCATION.putValue(null)
        }
        withLaunchFromLauncher { scenario ->
            openTestDirectory(scenario)
        }

        instrumentation.runOnMainSync {
            assertEquals(null, Settings.FILE_LIST_LAST_LOCATION.value)
        }
        withLaunchFromLauncher { scenario ->
            assertNotNull(device.wait(Until.findObject(By.text(directoryName)), 20_000))
            @Suppress("DEPRECATION")
            scenario.assertPathBecomes(Environment.getExternalStorageDirectory().path)
        }
    }

    @Test
    fun theSystemRecreatingTheListKeepsItWhereItWas() {
        instrumentation.runOnMainSync {
            Settings.FILE_LIST_REMEMBER_LAST_DIRECTORY.putValue(false)
            Settings.FILE_LIST_LAST_LOCATION.putValue(null)
        }
        withLaunchFromLauncher { scenario ->
            openTestDirectory(scenario)

            scenario.recreate()

            assertNotNull(device.wait(Until.findObject(By.text("Inside.txt")), 20_000))
            scenario.assertPathBecomes(directory.path)
        }
    }

    companion object {
        private const val LAUNCH_ATTEMPTS = 3
        private const val WINDOW_GONE_TIMEOUT_MILLIS = 10_000L
    }
}
