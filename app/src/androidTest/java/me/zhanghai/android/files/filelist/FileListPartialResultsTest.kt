package me.zhanghai.android.files.filelist

import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityEvent
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import me.zhanghai.android.files.captureReviewScreenshot
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.provider.root.RootStrategy
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Render controlled partial/loading states with real file rows, without a flaky network outage. */
class FileListPartialResultsTest {
    @Test fun partialResultsKeepRowsAndExplainMissingFiles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand(
            "appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow"
        ).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
        ).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
        val device = UiDevice.getInstance(instrumentation)
        val configurator = Configurator.getInstance()
        val previousIdleTimeout = configurator.waitForIdleTimeout
        // Observe short-lived toasts without waiting for animations to become idle.
        configurator.waitForIdleTimeout = 0
        val directory = File(context.cacheDir, "Project files").apply { mkdirs() }
        listOf("Meeting notes.txt", "Shopping list.txt", "Weekend plans.txt").forEach {
            File(directory, it).writeText("Review fixture")
        }
        var previous: RootStrategy? = null
        instrumentation.runOnMainSync {
            previous = Settings.ROOT_STRATEGY.value
            Settings.ROOT_STRATEGY.putValue(RootStrategy.NEVER)
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.fromFile(directory), "inode/directory")
                .setClass(context, FileListActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<FileListActivity>(intent).use { scenario ->
                assertNotNull(device.wait(Until.findObject(By.text("Meeting notes.txt")), 10000))
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.fragments
                        .filterIsInstance<FileListFragment>().single()
                    val state = fragment.viewModel.fileListLiveData
                        as MutableLiveData<Stateful<List<FileItem>>>
                    state.value = Loading(checkNotNull(state.value?.value))
                }
                assertNotNull(device.wait(Until.findObject(By.text("Loading…")), 10000))
                captureReviewScreenshot("folder-loading")
                val notification = instrumentation.uiAutomation.executeAndWaitForEvent(
                    {
                        scenario.onActivity { activity ->
                            val fragment = activity.supportFragmentManager.fragments
                                .filterIsInstance<FileListFragment>().single()
                            val state = fragment.viewModel.fileListLiveData
                                as MutableLiveData<Stateful<List<FileItem>>>
                            state.value = Failure(
                                checkNotNull(state.value?.value),
                                PartialFileListException(
                                    2,
                                    java.io.IOException("Fixture metadata failure")
                                )
                            )
                        }
                    },
                    { event ->
                        event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                            event.text.any { it.contains("Some files could not be read") }
                    },
                    5000
                )
                org.junit.Assert.assertTrue(notification.text.any { it.contains("2 missing") })
                assertNotNull(device.wait(Until.findObject(By.text("Error")), 3000))
                captureReviewScreenshot("folder-partial-results")
                assertNotNull(device.findObject(By.text("Meeting notes.txt")))
            }
        } finally {
            configurator.waitForIdleTimeout = previousIdleTimeout
            directory.deleteRecursively()
            instrumentation.runOnMainSync { previous?.let { Settings.ROOT_STRATEGY.putValue(it) } }
        }
    }
}
