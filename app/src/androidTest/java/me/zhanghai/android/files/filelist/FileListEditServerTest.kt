/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.FileInputStream
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.provider.common.AuthenticationFailedException
import me.zhanghai.android.files.provider.sftp.client.Authority as SftpAuthority
import me.zhanghai.android.files.provider.sftp.client.PasswordAuthentication
import me.zhanghai.android.files.provider.sftp.createSftpRootPath
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.storage.EditSftpServerActivity
import me.zhanghai.android.files.storage.SftpServer
import me.zhanghai.android.files.storage.Storage
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.valueCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A folder on a stored server that turned away its password offers to edit the server, and comes
 * back from editing it to try the folder again.
 */
@RunWith(AndroidJUnit4::class)
class FileListEditServerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    // Nothing listens on port 1, so listing the folder fails right away and without a password.
    private val authority = SftpAuthority("127.0.0.1", 1, "tester")
    private val server =
        SftpServer(null, "Test SFTP", authority, PasswordAuthentication("secret"), "")
    private lateinit var savedStorages: List<Storage>

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        savedStorages = Settings.STORAGES.valueCompat
        instrumentation.runOnMainSync { Settings.STORAGES.putValue(savedStorages + server) }
        waitFor("The server was never stored") {
            Settings.STORAGES.valueCompat.any { it.id == server.id }
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { Settings.STORAGES.putValue(savedStorages) }
    }

    @Test
    fun aTurnedAwayPasswordOffersToEditTheServerAndRetriesAfter() {
        val intent = FileListActivity.createViewIntent(authority.createSftpRootPath())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<FileListActivity>(intent).use { scenario ->
            // A refused connection is not about the password, so it offers only to retry.
            scenario.awaitState("The connection error never showed") {
                it.findViewById<View>(R.id.errorLayout).isVisible &&
                    it.findViewById<TextView>(R.id.errorText).text.isNotEmpty()
            }
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<View>(R.id.editServerButton).isVisible)
                assertTrue(activity.findViewById<View>(R.id.retryButton).isVisible)
            }

            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.fragments
                    .filterIsInstance<FileListFragment>().single()
                val state = fragment.viewModel.fileListLiveData
                    as MutableLiveData<Stateful<List<FileItem>>>
                state.value = Failure(
                    null,
                    AuthenticationFailedException(fragment.viewModel.currentPath.toString())
                )
            }
            scenario.awaitState("The edit server button never showed") {
                it.findViewById<View>(R.id.editServerButton).isVisible
            }
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.retryButton).isVisible)
                activity.findViewById<View>(R.id.editServerButton).performClick()
            }

            // The edit screen of the stored server opens, filled in with it.
            val editActivity = waitForResumedActivity<EditSftpServerActivity>()
            instrumentation.runOnMainSync {
                assertEquals(
                    "127.0.0.1",
                    editActivity.findViewById<EditText>(R.id.hostEdit).text.toString()
                )
                editActivity.finish()
            }

            // Back from editing, the folder is listed again, and fails to connect again.
            scenario.awaitState("The folder was not listed again after editing the server") {
                !it.findViewById<View>(R.id.editServerButton).isVisible &&
                    it.findViewById<View>(R.id.errorLayout).isVisible
            }
        }
    }

    private inline fun <reified T : Activity> waitForResumedActivity(): T {
        var activity: T? = null
        waitFor("${T::class.java.simpleName} never resumed") {
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<T>().firstOrNull()
            }
            activity != null
        }
        return activity!!
    }

    private fun ActivityScenario<FileListActivity>.awaitState(
        message: String,
        predicate: (FileListActivity) -> Boolean
    ) {
        waitFor(message) {
            var isMet = false
            onActivity { isMet = predicate(it) }
            isMet
        }
    }

    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(100)
        }
        throw AssertionError(message)
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }
}
