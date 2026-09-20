/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputLayout
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.smb.client.Authority
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.valueCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The four "add a server" screens set up their toolbar and refuse to connect without a host.
 */
@RunWith(AndroidJUnit4::class)
class EditServerFragmentsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun addSmbServerShowsItsTitleAndNeedsAHost() {
        checkAddServerScreen(
            Intent(context, EditSmbServerActivity::class.java)
                .putArgs(EditSmbServerFragment.Args()),
            R.string.storage_edit_smb_server_title_add,
            R.string.storage_edit_smb_server_host_error_empty
        )
    }

    @Test
    fun addSftpServerShowsItsTitleAndNeedsAHost() {
        checkAddServerScreen(
            Intent(context, EditSftpServerActivity::class.java)
                .putArgs(EditSftpServerFragment.Args()),
            R.string.storage_edit_sftp_server_title_add,
            R.string.storage_edit_sftp_server_host_error_empty
        )
    }

    @Test
    fun addFtpServerShowsItsTitleAndNeedsAHost() {
        checkAddServerScreen(
            Intent(context, EditFtpServerActivity::class.java)
                .putArgs(EditFtpServerFragment.Args()),
            R.string.storage_edit_ftp_server_title_add,
            R.string.storage_edit_ftp_server_host_error_empty
        )
    }

    @Test
    fun addWebDavServerShowsItsTitleAndNeedsAHost() {
        checkAddServerScreen(
            Intent(context, EditWebDavServerActivity::class.java)
                .putArgs(EditWebDavServerFragment.Args()),
            R.string.storage_edit_webdav_server_title_add,
            R.string.storage_edit_webdav_server_host_error_empty
        )
    }

    @Test
    fun editingAnSmbServerFillsInTheFormAndSavesTheChanges() {
        val savedStorages = Settings.STORAGES.valueCompat
        val server = SmbServer(
            null,
            "Test SMB",
            Authority("10.0.2.2", Authority.DEFAULT_PORT, "tester", "WORKGROUP"),
            "secret",
            "share"
        )
        Settings.STORAGES.putValue(savedStorages + server)
        waitForStorages { it.any { storage -> storage.id == server.id } }
        try {
            val intent = Intent(context, EditSmbServerActivity::class.java)
                .putArgs(EditSmbServerFragment.Args(server))
            ActivityScenario.launch<Activity>(intent).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals(
                        context.getString(R.string.storage_edit_smb_server_title_edit),
                        activity.title
                    )
                    assertEquals("10.0.2.2", activity.textOf(R.id.hostEdit))
                    assertEquals("tester", activity.textOf(R.id.usernameEdit))
                    assertEquals("WORKGROUP", activity.textOf(R.id.domainEdit))
                    assertEquals("secret", activity.textOf(R.id.passwordEdit))
                    assertEquals("share", activity.textOf(R.id.pathEdit))
                    assertEquals("Test SMB", activity.textOf(R.id.nameEdit))
                    assertEquals(
                        context.getString(R.string.save),
                        activity.findViewById<Button>(R.id.saveOrConnectAndAddButton).text
                    )
                    // The name field shows what the server would be called without a custom name.
                    assertEquals(
                        "WORKGROUP\\tester@10.0.2.2/share",
                        activity.findViewById<TextInputLayout>(R.id.nameLayout).placeholderText
                    )

                    activity.findViewById<EditText>(R.id.pathEdit).setText("other")
                    activity.findViewById<Button>(R.id.saveOrConnectAndAddButton).performClick()
                }
                instrumentation.waitForIdleSync()
            }

            val storages = Settings.STORAGES.valueCompat
            assertEquals(savedStorages.size + 1, storages.size)
            val savedServer = storages.filterIsInstance<SmbServer>().single { it.id == server.id }
            assertEquals("other", savedServer.relativePath)
            assertEquals("Test SMB", savedServer.customName)
            assertEquals(server.authority, savedServer.authority)
            assertEquals("secret", savedServer.password)
        } finally {
            Settings.STORAGES.putValue(savedStorages)
            waitForStorages { it.none { storage -> storage.id == server.id } }
        }
    }

    /** The setting is written through shared preferences, so the list settles a moment later. */
    private fun waitForStorages(predicate: (List<Storage>) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate(Settings.STORAGES.valueCompat)) {
                return
            }
            Thread.sleep(50)
        }
        throw AssertionError("The storage list never settled: \${Settings.STORAGES.valueCompat}")
    }

    private fun Activity.textOf(id: Int): String = findViewById<EditText>(id).text.toString()

    private fun checkAddServerScreen(intent: Intent, titleRes: Int, hostErrorRes: Int) {
        val storagesBefore = Settings.STORAGES.valueCompat
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(context.getString(titleRes), activity.title)
                val scrollView = activity.findViewById<View>(R.id.scrollView)
                assertEquals(View.VISIBLE, scrollView.visibility)
                activity.findViewById<Button>(R.id.saveOrConnectAndAddButton).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val hostLayout = activity.findViewById<TextInputLayout>(R.id.hostLayout)
                assertEquals(context.getString(hostErrorRes), hostLayout.error)
                // Without a host nothing is added and the screen stays open.
                assertFalse(activity.isFinishing)
            }
        }
        assertEquals(storagesBefore, Settings.STORAGES.valueCompat)
    }
}
