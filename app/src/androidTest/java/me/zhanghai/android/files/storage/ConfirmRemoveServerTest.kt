/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import me.zhanghai.android.files.R
import me.zhanghai.android.files.UiFailureDiagnosticsRule
import me.zhanghai.android.files.provider.ftp.client.Authority as FtpAuthority
import me.zhanghai.android.files.provider.ftp.client.Mode as FtpMode
import me.zhanghai.android.files.provider.ftp.client.Protocol as FtpProtocol
import me.zhanghai.android.files.provider.sftp.client.Authority as SftpAuthority
import me.zhanghai.android.files.provider.sftp.client.PasswordAuthentication as SftpPassword
import me.zhanghai.android.files.provider.smb.client.Authority as SmbAuthority
import me.zhanghai.android.files.provider.webdav.client.Authority as WebDavAuthority
import me.zhanghai.android.files.provider.webdav.client.PasswordAuthentication as WebDavPassword
import me.zhanghai.android.files.provider.webdav.client.Protocol as WebDavProtocol
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.valueCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Removing a saved server takes its password with it and cannot be undone, so each of the four
 * server screens asks first, and still asks after the screen is rotated.
 */
@RunWith(AndroidJUnit4::class)
class ConfirmRemoveServerTest {
    @get:Rule
    val diagnostics = UiFailureDiagnosticsRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private lateinit var savedStorages: List<Storage>

    @Before
    fun setUp() {
        savedStorages = Settings.STORAGES.valueCompat
    }

    @After
    fun tearDown() {
        Settings.STORAGES.putValue(savedStorages)
        val savedIds = savedStorages.map { it.id }
        waitForStorages { storages -> storages.map { it.id } == savedIds }
    }

    @Test
    fun cancellingKeepsTheSmbServerAndConfirmingAfterRotationRemovesIt() {
        val server = SmbServer(
            null,
            "Test SMB",
            SmbAuthority("10.0.2.2", SmbAuthority.DEFAULT_PORT, "tester", "WORKGROUP"),
            "secret",
            "share"
        )
        val intent = Intent(context, EditSmbServerActivity::class.java)
            .putArgs(EditSmbServerFragment.Args(server))
        checkRemoveAsksFirst(server, intent)
    }

    @Test
    fun removingAnSftpServerAsksFirst() {
        val server = SftpServer(
            null,
            "Test SFTP",
            SftpAuthority("10.0.2.2", 2222, "tester"),
            SftpPassword("secret"),
            ""
        )
        val intent = Intent(context, EditSftpServerActivity::class.java)
            .putArgs(EditSftpServerFragment.Args(server))
        checkRemoveAsksFirst(server, intent)
    }

    @Test
    fun removingAnFtpServerAsksFirst() {
        val server = FtpServer(
            null,
            "Test FTP",
            FtpAuthority(
                FtpProtocol.FTP,
                "10.0.2.2",
                FtpProtocol.FTP.defaultPort,
                "tester",
                FtpMode.PASSIVE,
                FtpAuthority.DEFAULT_ENCODING
            ),
            "secret",
            ""
        )
        val intent = Intent(context, EditFtpServerActivity::class.java)
            .putArgs(EditFtpServerFragment.Args(server))
        checkRemoveAsksFirst(server, intent)
    }

    @Test
    fun removingAWebDavServerAsksFirst() {
        val server = WebDavServer(
            null,
            "Test WebDAV",
            WebDavAuthority(WebDavProtocol.DAV, "10.0.2.2", 8081, "tester"),
            WebDavPassword("secret"),
            ""
        )
        val intent = Intent(context, EditWebDavServerActivity::class.java)
            .putArgs(EditWebDavServerFragment.Args(server))
        checkRemoveAsksFirst(server, intent)
    }

    private fun checkRemoveAsksFirst(server: Storage, intent: Intent) {
        Settings.STORAGES.putValue(savedStorages + server)
        waitForStorages { it.any { storage -> storage.id == server.id } }
        val title = context.getString(
            R.string.storage_edit_server_remove_title_format,
            server.getName(context)
        )
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()

            // Cancelling leaves the server as it was.
            clickRemove(scenario)
            assertNotNull(
                "No confirmation was asked for",
                device.wait(Until.findObject(By.text(title)), TIMEOUT_MILLIS)
            )
            assertNotNull(
                "The dialog does not say the password goes too",
                device.findObject(
                    By.text(context.getString(R.string.storage_edit_server_remove_message))
                )
            )
            device.findObject(By.res("android", "button2")).click()
            assertTrue(device.wait(Until.gone(By.text(title)), TIMEOUT_MILLIS))
            instrumentation.waitForIdleSync()
            assertTrue(Settings.STORAGES.valueCompat.any { it.id == server.id })
            scenario.onActivity { assertFalse(it.isFinishing) }

            // The question survives a rotation, and the answer still reaches the screen.
            clickRemove(scenario)
            assertNotNull(device.wait(Until.findObject(By.text(title)), TIMEOUT_MILLIS))
            scenario.recreate()
            assertNotNull(
                "The dialog did not survive recreation",
                device.wait(Until.findObject(By.text(title)), TIMEOUT_MILLIS)
            )
            device.findObject(By.res("android", "button1")).click()

            waitForStorages { it.none { storage -> storage.id == server.id } }
            val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
            while (scenario.state != Lifecycle.State.DESTROYED &&
                SystemClock.uptimeMillis() < deadline
            ) {
                Thread.sleep(POLL_MILLIS)
            }
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    private fun clickRemove(scenario: ActivityScenario<Activity>) {
        scenario.onActivity { activity ->
            val button = activity.findViewById<Button>(R.id.removeOrAddButton)
            assertEquals(context.getString(R.string.remove), button.text.toString())
            button.performClick()
        }
    }

    /** The setting is written through shared preferences, so the list settles a moment later. */
    private fun waitForStorages(predicate: (List<Storage>) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate(Settings.STORAGES.valueCompat)) {
                return
            }
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("The storage list never settled: ${Settings.STORAGES.valueCompat}")
    }

    companion object {
        private const val TIMEOUT_MILLIS = 10_000L
        private const val POLL_MILLIS = 50L
    }
}
