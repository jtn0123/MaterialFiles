/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputLayout
import me.zhanghai.android.files.R
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
