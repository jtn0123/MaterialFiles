/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import androidx.annotation.ArrayRes
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputLayout
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.ftp.client.Authority as FtpAuthority
import me.zhanghai.android.files.provider.ftp.client.Protocol as FtpProtocol
import me.zhanghai.android.files.provider.sftp.client.Authority as SftpAuthority
import me.zhanghai.android.files.provider.sftp.client.PasswordAuthentication as SftpPasswordAuthentication
import me.zhanghai.android.files.provider.webdav.client.AccessTokenAuthentication
import me.zhanghai.android.files.provider.webdav.client.Authority as WebDavAuthority
import me.zhanghai.android.files.provider.webdav.client.Protocol as WebDavProtocol
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.valueCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The server forms show an error on every wrong field, focus the first one, and build the server
 * from the fields once they are all right.
 */
@RunWith(AndroidJUnit4::class)
class ServerFormValidationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun everyWrongSftpFieldShowsItsErrorAndTheFirstTakesTheFocus() {
        val intent = Intent(context, EditSftpServerActivity::class.java)
            .putArgs(EditSftpServerFragment.Args())
        val storagesBefore = Settings.STORAGES.valueCompat
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.portEdit).setText("ssh")
                activity.selectItem(
                    R.id.authenticationTypeEdit,
                    R.array.storage_edit_sftp_server_authentication_type_entries,
                    1
                )
                activity.findViewById<EditText>(R.id.privateKeyEdit).setText("not a key")
                activity.findViewById<Button>(R.id.removeOrAddButton).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                activity.assertError(
                    R.id.hostLayout,
                    R.string.storage_edit_sftp_server_host_error_empty
                )
                activity.assertError(
                    R.id.portLayout,
                    R.string.storage_edit_sftp_server_port_error_invalid
                )
                activity.assertError(
                    R.id.usernameLayout,
                    R.string.storage_edit_sftp_server_username_error_empty
                )
                activity.assertError(
                    R.id.privateKeyLayout,
                    R.string.storage_edit_sftp_server_private_key_error_invalid
                )
                assertTrue(activity.findViewById<EditText>(R.id.hostEdit).isFocused)
                assertFalse(activity.isFinishing)
            }
        }
        assertEquals(storagesBefore, Settings.STORAGES.valueCompat)
    }

    @Test
    fun aWebDavServerNeedsItsAccessTokenAndIsAddedWithIt() {
        val intent = Intent(context, EditWebDavServerActivity::class.java)
            .putArgs(EditWebDavServerFragment.Args())
        val storagesBefore = Settings.STORAGES.valueCompat
        try {
            ActivityScenario.launch<Activity>(intent).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.hostEdit).setText("example.com")
                    activity.selectItem(
                        R.id.authenticationTypeEdit,
                        R.array.storage_edit_webdav_server_authentication_type_entries,
                        1
                    )
                    activity.findViewById<Button>(R.id.removeOrAddButton).performClick()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    activity.assertError(
                        R.id.accessTokenLayout,
                        R.string.storage_edit_webdav_server_access_token_error_empty
                    )
                    assertNull(activity.findViewById<TextInputLayout>(R.id.hostLayout).error)
                    assertTrue(activity.findViewById<EditText>(R.id.accessTokenEdit).isFocused)

                    activity.findViewById<EditText>(R.id.accessTokenEdit).setText("token")
                    activity.findViewById<Button>(R.id.removeOrAddButton).performClick()
                }
                instrumentation.waitForIdleSync()
            }

            waitForStorages { it.size == storagesBefore.size + 1 }
            val server = Settings.STORAGES.valueCompat.filterIsInstance<WebDavServer>()
                .single { it.authority.host == "example.com" }
            assertEquals(AccessTokenAuthentication("token"), server.authentication)
        } finally {
            Settings.STORAGES.putValue(storagesBefore)
            waitForStorages { it == storagesBefore }
        }
    }

    @Test
    fun editingAnAnonymousFtpServerKeepsItAnonymous() {
        val savedStorages = Settings.STORAGES.valueCompat
        val server = FtpServer(
            null,
            null,
            FtpAuthority(
                FtpProtocol.FTP,
                "10.0.2.2",
                FtpProtocol.FTP.defaultPort,
                FtpAuthority.ANONYMOUS_USERNAME,
                FtpAuthority.DEFAULT_MODE,
                FtpAuthority.DEFAULT_ENCODING
            ),
            FtpAuthority.ANONYMOUS_PASSWORD,
            ""
        )
        Settings.STORAGES.putValue(savedStorages + server)
        waitForStorages { it.any { storage -> storage.id == server.id } }
        try {
            val intent = Intent(context, EditFtpServerActivity::class.java)
                .putArgs(EditFtpServerFragment.Args(server))
            ActivityScenario.launch<Activity>(intent).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals(
                        context.resources.getTextArray(
                            R.array.storage_edit_ftp_server_authentication_type_entries
                        )[1].toString(),
                        activity.findViewById<EditText>(R.id.authenticationTypeEdit).text
                            .toString()
                    )
                    activity.findViewById<EditText>(R.id.pathEdit).setText("pub")
                    activity.findViewById<Button>(R.id.saveOrConnectAndAddButton).performClick()
                }
                instrumentation.waitForIdleSync()
            }

            waitForStorages { storages ->
                storages.any { it is FtpServer && it.id == server.id && it.relativePath == "pub" }
            }
            val savedServer = Settings.STORAGES.valueCompat.filterIsInstance<FtpServer>()
                .single { it.id == server.id }
            assertEquals(server.authority, savedServer.authority)
            assertEquals(FtpAuthority.ANONYMOUS_PASSWORD, savedServer.password)
        } finally {
            Settings.STORAGES.putValue(savedStorages)
            waitForStorages { it.none { storage -> storage.id == server.id } }
        }
    }

    @Test
    fun editingAnSftpServerFillsInTheFormAndSavesIt() {
        val server = SftpServer(
            null,
            "Test SFTP",
            SftpAuthority("10.0.2.2", 2222, "tester"),
            SftpPasswordAuthentication("secret"),
            "home"
        )
        checkEditRoundTrip(
            server,
            { (it as SftpServer).relativePath },
            Intent(context, EditSftpServerActivity::class.java)
                .putArgs(EditSftpServerFragment.Args(server))
        ) { activity ->
            assertEquals("2222", activity.textOf(R.id.portEdit))
            assertEquals("tester", activity.textOf(R.id.usernameEdit))
            assertEquals("secret", activity.textOf(R.id.passwordEdit))
        }
    }

    @Test
    fun editingAWebDavServerFillsInTheFormAndSavesIt() {
        val server = WebDavServer(
            null,
            "Test WebDAV",
            WebDavAuthority(WebDavProtocol.DAVS, "example.com", 8443, ""),
            AccessTokenAuthentication("token"),
            "dav"
        )
        checkEditRoundTrip(
            server,
            { (it as WebDavServer).relativePath },
            Intent(context, EditWebDavServerActivity::class.java)
                .putArgs(EditWebDavServerFragment.Args(server))
        ) { activity ->
            assertEquals("8443", activity.textOf(R.id.portEdit))
            assertEquals("token", activity.textOf(R.id.accessTokenEdit))
        }
    }

    /**
     * Opens [server] for editing, checks the form with [checkForm], changes the path and saves,
     * then checks that only the path changed.
     */
    private fun checkEditRoundTrip(
        server: Storage,
        relativePathOf: (Storage) -> String,
        intent: Intent,
        checkForm: (Activity) -> Unit
    ) {
        val savedStorages = Settings.STORAGES.valueCompat
        Settings.STORAGES.putValue(savedStorages + server)
        waitForStorages { it.any { storage -> storage.id == server.id } }
        try {
            ActivityScenario.launch<Activity>(intent).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals(server.customName, activity.textOf(R.id.nameEdit))
                    checkForm(activity)
                    activity.findViewById<EditText>(R.id.pathEdit).setText("other")
                    activity.findViewById<Button>(R.id.saveOrConnectAndAddButton).performClick()
                }
                instrumentation.waitForIdleSync()
            }

            waitForStorages { storages ->
                storages.any { it.id == server.id && relativePathOf(it) == "other" }
            }
            val savedServer = Settings.STORAGES.valueCompat.single { it.id == server.id }
            assertEquals(server.customName, savedServer.customName)
        } finally {
            Settings.STORAGES.putValue(savedStorages)
            waitForStorages { it.none { storage -> storage.id == server.id } }
        }
    }

    private fun Activity.textOf(id: Int): String = findViewById<EditText>(id).text.toString()

    private fun Activity.selectItem(id: Int, @ArrayRes entriesRes: Int, index: Int) {
        val item = resources.getTextArray(entriesRes)[index]
        findViewById<AutoCompleteTextView>(id).setText(item, false)
    }

    private fun Activity.assertError(layoutId: Int, errorRes: Int) {
        assertEquals(getString(errorRes), findViewById<TextInputLayout>(layoutId).error)
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
        throw AssertionError("The storage list never settled: ${Settings.STORAGES.valueCompat}")
    }
}
