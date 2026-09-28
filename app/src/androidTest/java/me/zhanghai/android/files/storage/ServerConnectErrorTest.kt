/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.ArrayRes
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java8.nio.file.Paths
import kotlin.concurrent.thread
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.valueCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Failing to connect to a new server leaves the form in sight, says why above it and marks the
 * field that is likely wrong, instead of a toast with the exception in it.
 */
@RunWith(AndroidJUnit4::class)
class ServerConnectErrorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun aServerThatRefusesTheConnectionMarksTheHost() {
        checkRefusedConnectionMarksTheHost(
            Intent(context, EditSmbServerActivity::class.java)
                .putArgs(EditSmbServerFragment.Args())
        ) { activity ->
            activity.findViewById<EditText>(R.id.usernameEdit).setText("tester")
        }
    }

    @Test
    fun anSftpServerThatRefusesTheConnectionMarksTheHost() {
        checkRefusedConnectionMarksTheHost(
            Intent(context, EditSftpServerActivity::class.java)
                .putArgs(EditSftpServerFragment.Args())
        ) { activity ->
            activity.findViewById<EditText>(R.id.usernameEdit).setText("tester")
            activity.findViewById<EditText>(R.id.passwordEdit).setText("secret")
        }
    }

    @Test
    fun anFtpServerThatRefusesTheConnectionMarksTheHost() {
        checkRefusedConnectionMarksTheHost(
            Intent(context, EditFtpServerActivity::class.java)
                .putArgs(EditFtpServerFragment.Args())
        ) { activity ->
            activity.findViewById<EditText>(R.id.usernameEdit).setText("tester")
            activity.findViewById<EditText>(R.id.passwordEdit).setText("secret")
        }
    }

    @Test
    fun aWebDavServerThatRefusesTheConnectionMarksTheHostWhateverTheAuthentication() {
        val authenticationTypes = R.array.storage_edit_webdav_server_authentication_type_entries
        // Password, access token and none.
        for (index in 0..2) {
            checkRefusedConnectionMarksTheHost(
                Intent(context, EditWebDavServerActivity::class.java)
                    .putArgs(EditWebDavServerFragment.Args())
            ) { activity ->
                activity.selectItem(R.id.authenticationTypeEdit, authenticationTypes, index)
                activity.findViewById<EditText>(R.id.usernameEdit).setText("tester")
                activity.findViewById<EditText>(R.id.passwordEdit).setText("secret")
                activity.findViewById<EditText>(R.id.accessTokenEdit).setText("token")
            }
        }
    }

    @Test
    fun aWebDavServerThatTurnsAwayThePasswordMarksThePassword() {
        val server = startUnauthorizedHttpServer()
        val storagesBefore = Settings.STORAGES.valueCompat
        try {
            val intent = Intent(context, EditWebDavServerActivity::class.java)
                .putArgs(EditWebDavServerFragment.Args())
            ActivityScenario.launch<Activity>(intent).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    activity.selectItem(
                        R.id.protocolEdit,
                        R.array.storage_edit_webdav_server_protocol_entries,
                        0
                    )
                    activity.findViewById<EditText>(R.id.hostEdit).setText("127.0.0.1")
                    activity.findViewById<EditText>(R.id.portEdit)
                        .setText(server.localPort.toString())
                    activity.findViewById<EditText>(R.id.usernameEdit).setText("tester")
                    activity.findViewById<EditText>(R.id.passwordEdit).setText("wrong")
                    activity.findViewById<Button>(R.id.saveOrConnectAndAddButton).performClick()
                }
                waitForConnectError(scenario)
                scenario.onActivity { activity ->
                    assertNotNull(activity.findViewById<TextInputLayout>(R.id.passwordLayout).error)
                    assertNull(activity.findViewById<TextInputLayout>(R.id.hostLayout).error)
                    assertTrue(activity.findViewById<EditText>(R.id.passwordEdit).isFocused)
                    assertTrue(
                        activity.findViewById<View>(R.id.saveOrConnectAndAddButton).isEnabled
                    )
                    assertFalse(activity.isFinishing)
                }
            }
        } finally {
            server.close()
        }
        assertEquals(storagesBefore, Settings.STORAGES.valueCompat)
    }

    @Test
    fun aPrivateKeyFileThatCannotBeReadSaysWhyOnTheKeyField() {
        val intent = Intent(context, EditSftpServerActivity::class.java)
            .putArgs(EditSftpServerFragment.Args())
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                activity.selectItem(
                    R.id.authenticationTypeEdit,
                    R.array.storage_edit_sftp_server_authentication_type_entries,
                    1
                )
                val fragment = (activity as EditSftpServerActivity).supportFragmentManager
                    .fragments.filterIsInstance<EditSftpServerFragment>().single()
                // The fragment's view model, as picking a file in the system picker would use it.
                val viewModel = ViewModelProvider(fragment)
                    .get(EditSftpServerViewModel::class.java)
                val missingFile = File(context.filesDir, "no-such-key")
                viewModel.readPrivateKeyFile(Paths.get(missingFile.path))
            }
            waitUntil(scenario, "The key file error never showed") {
                it.findViewById<TextInputLayout>(R.id.privateKeyLayout).error != null
            }
            scenario.onActivity { activity ->
                assertEquals(
                    "",
                    activity.findViewById<EditText>(R.id.privateKeyEdit).text.toString()
                )
                assertNull(
                    activity.findViewById<TextInputLayout>(R.id.privateKeyLayout).placeholderText
                )
                assertFalse(activity.isFinishing)
            }
        }
    }

    /**
     * Fills in the form of [intent] with a server that refuses the connection, after [fillForm]
     * filled in the rest, and checks that connecting marks the host and leaves the form usable.
     */
    private fun checkRefusedConnectionMarksTheHost(intent: Intent, fillForm: (Activity) -> Unit) {
        val storagesBefore = Settings.STORAGES.valueCompat
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                fillForm(activity)
                activity.findViewById<EditText>(R.id.hostEdit).setText("127.0.0.1")
                // Nothing listens on port 1, so the connection is refused right away.
                activity.findViewById<EditText>(R.id.portEdit).setText("1")
                activity.findViewById<Button>(R.id.saveOrConnectAndAddButton).performClick()
            }
            waitForConnectError(scenario)
            scenario.onActivity { activity ->
                val errorText = activity.findViewById<TextView>(R.id.connectErrorText).text
                val connectionFailed = context.getString(R.string.error_connection_failed)
                assertTrue(errorText.toString(), errorText.startsWith(connectionFailed))
                assertEquals(
                    connectionFailed,
                    activity.findViewById<TextInputLayout>(R.id.hostLayout).error
                )
                // The form is still there and usable to fix the host, which has the focus.
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.scrollView).visibility)
                assertTrue(activity.findViewById<View>(R.id.hostEdit).isEnabled)
                assertTrue(activity.findViewById<View>(R.id.hostEdit).isFocused)
                assertTrue(activity.findViewById<View>(R.id.saveOrConnectAndAddButton).isEnabled)
                assertFalse(activity.isFinishing)
            }
        }
        assertEquals(storagesBefore, Settings.STORAGES.valueCompat)
    }

    private fun waitForConnectError(scenario: ActivityScenario<Activity>) {
        waitUntil(scenario, "The connect error never showed") {
            it.findViewById<View>(R.id.connectErrorText).visibility == View.VISIBLE
        }
    }

    private fun waitUntil(
        scenario: ActivityScenario<Activity>,
        message: String,
        predicate: (Activity) -> Boolean
    ) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            var isMet = false
            scenario.onActivity { isMet = predicate(it) }
            if (isMet) {
                return
            }
            Thread.sleep(100)
        }
        throw AssertionError(message)
    }

    private fun Activity.selectItem(id: Int, @ArrayRes entriesRes: Int, index: Int) {
        val item = resources.getTextArray(entriesRes)[index]
        findViewById<AutoCompleteTextView>(id).setText(item, false)
    }

    /** An HTTP server on the loopback that turns away every request as unauthorized. */
    private fun startUnauthorizedHttpServer(): ServerSocket {
        val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (true) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: IOException) {
                    break
                }
                thread(isDaemon = true) {
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        var contentLength = 0
                        while (true) {
                            val line = reader.readLine()
                            if (line.isNullOrEmpty()) {
                                break
                            }
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(':').trim().toInt()
                            }
                        }
                        repeat(contentLength) { reader.read() }
                        it.getOutputStream().write(
                            (
                                "HTTP/1.1 401 Unauthorized\r\n" +
                                    "WWW-Authenticate: Basic realm=\"test\"\r\n" +
                                    "Content-Length: 0\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray()
                        )
                    }
                }
            }
        }
        return serverSocket
    }
}
