/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.saveas

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java8.nio.file.Paths
import me.zhanghai.android.files.NoRootAccessRule
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.filelist.FileListFragment
import me.zhanghai.android.files.filelist.PickOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Where what another app shares is saved, and under which name. */
@RunWith(AndroidJUnit4::class)
class SaveAsActivityTest {
    @get:Rule
    val noRootAccess = NoRootAccessRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var directory: File

    @Before
    fun setUp() {
        executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        executeShellCommand(
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
        )
        directory = File(context.cacheDir, "save-as-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        // Finishing the picker cancels it, which in turn finishes the activity waiting for it.
        repeat(2) {
            instrumentation.runOnMainSync { resumedActivities().forEach { it.finish() } }
            instrumentation.waitForIdleSync()
        }
        directory.deleteRecursively()
    }

    private fun executeShellCommand(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun sharedUri(name: String): Uri =
        Paths.get(File(directory, name).apply { writeText("Shared contents") }.path).fileProviderUri

    /** Starts the activity that saves what was shared, without waiting for it to settle. */
    private fun startSaveAs(intent: Intent): Activity {
        val monitor = instrumentation.addMonitor(SaveAsActivity::class.java.name, null, false)
        context.startActivity(
            intent.setClass(context, SaveAsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val activity = instrumentation.waitForMonitorWithTimeout(monitor, 20_000)
        instrumentation.removeMonitor(monitor)
        assertNotNull("The save-as activity never started", activity)
        return activity
    }

    /** The options the picker that the save-as activity started is running with. */
    private fun awaitPickOptions(): PickOptions {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            var pickOptions: PickOptions? = null
            instrumentation.runOnMainSync {
                pickOptions = resumedActivities()
                    .filterIsInstance<FileListActivity>()
                    .firstOrNull()
                    ?.supportFragmentManager
                    ?.fragments
                    ?.filterIsInstance<FileListFragment>()
                    ?.singleOrNull()
                    ?.viewModel
                    ?.pickOptions
            }
            pickOptions?.let { return it }
            Thread.sleep(200)
        }
        throw AssertionError("The picker never opened")
    }

    private fun resumedActivities(): Collection<Activity> =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)

    @Test
    fun oneSharedFileIsSavedUnderTheNameItsProviderGives() {
        startSaveAs(
            Intent(Intent.ACTION_SEND)
                .setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, sharedUri("Shared photo.jpg"))
        )

        val pickOptions = awaitPickOptions()

        assertEquals(PickOptions.Mode.CREATE_FILE, pickOptions.mode)
        assertEquals("Shared photo.jpg", pickOptions.fileName)
        assertEquals(listOf("image/jpeg"), pickOptions.mimeTypes.map { it.value })
    }

    @Test
    fun aSharedFileUriKeepsItsOwnFileName() {
        val file = File(directory, "From storage.txt").apply { writeText("Shared contents") }
        startSaveAs(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(file), "text/plain"))

        val pickOptions = awaitPickOptions()

        assertEquals(PickOptions.Mode.CREATE_FILE, pickOptions.mode)
        assertEquals("From storage.txt", pickOptions.fileName)
    }

    @Test
    fun severalSharedFilesAskForAFolderInstead() {
        startSaveAs(
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("image/jpeg")
                .putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    arrayListOf(sharedUri("First.jpg"), sharedUri("Second.jpg"))
                )
        )

        val pickOptions = awaitPickOptions()

        assertEquals(PickOptions.Mode.OPEN_DIRECTORY, pickOptions.mode)
        assertFalse(pickOptions.allowMultiple)
    }

    @Test
    fun anIntentWithNothingToSaveGivesUpAtOnce() {
        val activity = startSaveAs(Intent(Intent.ACTION_SEND).setType("image/jpeg"))

        val deadline = System.currentTimeMillis() + 20_000
        while (
            !activity.isFinishing && !activity.isDestroyed &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(200)
        }
        assertTrue("The save-as activity kept running", activity.isFinishing)
        instrumentation.runOnMainSync {
            assertFalse(resumedActivities().any { it is FileListActivity })
        }
    }
}
