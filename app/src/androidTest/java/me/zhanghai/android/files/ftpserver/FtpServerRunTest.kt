/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ftpserver

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.net.Socket
import java8.nio.file.Paths
import me.zhanghai.android.files.provider.root.RootStrategy
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The FTP server as a user sees it: it serves the home directory over the network and accepts
 * uploads when it is writable.
 */
@RunWith(AndroidJUnit4::class)
class FtpServerRunTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private lateinit var homeDirectory: File
    private lateinit var savedSettings: Map<String, Any>
    private var scenario: ActivityScenario<FtpServerActivity>? = null

    @Before
    fun setUp() {
        homeDirectory = File(context.filesDir, "ftp-server-test").apply {
            deleteRecursively()
            mkdirs()
        }
        File(homeDirectory, "hello.txt").writeText(FILE_CONTENTS)
        savedSettings = mapOf(
            "anonymous" to Settings.FTP_SERVER_ANONYMOUS_LOGIN.valueCompat,
            "port" to Settings.FTP_SERVER_PORT.valueCompat,
            "home" to Settings.FTP_SERVER_HOME_DIRECTORY.valueCompat,
            "writable" to Settings.FTP_SERVER_WRITABLE.valueCompat,
            "passivePorts" to Settings.FTP_SERVER_PASSIVE_PORTS.valueCompat,
            "rootStrategy" to Settings.ROOT_STRATEGY.valueCompat
        )
        instrumentation.runOnMainSync {
            Settings.FTP_SERVER_ANONYMOUS_LOGIN.putValue(true)
            Settings.FTP_SERVER_PORT.putValue(PORT)
            Settings.FTP_SERVER_HOME_DIRECTORY.putValue(Paths.get(homeDirectory.path))
            Settings.FTP_SERVER_WRITABLE.putValue(true)
            Settings.FTP_SERVER_PASSIVE_PORTS.putValue("")
            // The app's own cache directory is not reachable as root on the emulator.
            Settings.ROOT_STRATEGY.putValue(RootStrategy.NEVER)
        }
        // A foreground service may only be started while the app is in the foreground.
        scenario = ActivityScenario.launch(FtpServerActivity::class.java)
    }

    @After
    fun tearDown() {
        FtpServerService.stop(context)
        waitForState(FtpServerService.State.STOPPED)
        scenario?.close()
        instrumentation.runOnMainSync {
            Settings.FTP_SERVER_ANONYMOUS_LOGIN.putValue(savedSettings["anonymous"] as Boolean)
            Settings.FTP_SERVER_PORT.putValue(savedSettings["port"] as Int)
            Settings.FTP_SERVER_HOME_DIRECTORY.putValue(
                savedSettings["home"] as java8.nio.file.Path
            )
            Settings.FTP_SERVER_WRITABLE.putValue(savedSettings["writable"] as Boolean)
            Settings.FTP_SERVER_PASSIVE_PORTS.putValue(savedSettings["passivePorts"] as String)
            Settings.ROOT_STRATEGY.putValue(savedSettings["rootStrategy"] as RootStrategy)
        }
        homeDirectory.deleteRecursively()
    }

    @Test
    fun theServerServesItsHomeDirectoryAndAcceptsAnUpload() {
        FtpServerService.start(context)
        waitForState(FtpServerService.State.RUNNING)

        FtpClient(PORT).use { client ->
            assertTrue(
                "Expected a greeting but got ${client.greeting}",
                client.greeting.startsWith("220")
            )
            client.login()

            val listing = client.transfer("LIST")!!.decodeToString()
            assertTrue("Expected hello.txt in:\n$listing", listing.contains("hello.txt"))

            val downloaded = client.transfer("RETR hello.txt")!!.decodeToString()
            assertEquals(FILE_CONTENTS, downloaded)

            client.transfer("STOR uploaded.txt", UPLOAD_CONTENTS.toByteArray())
            assertEquals(UPLOAD_CONTENTS, File(homeDirectory, "uploaded.txt").readText())

            assertTrue(client.command("QUIT").startsWith("221"))
        }
    }

    @Test
    fun aReadOnlyServerRefusesAnUpload() {
        instrumentation.runOnMainSync { Settings.FTP_SERVER_WRITABLE.putValue(false) }

        FtpServerService.start(context)
        waitForState(FtpServerService.State.RUNNING)

        FtpClient(PORT).use { client ->
            client.login()
            val reply = client.transferReply("STOR uploaded.txt", UPLOAD_CONTENTS.toByteArray())
            assertTrue(
                "A read-only server must refuse an upload but replied $reply",
                reply.startsWith("5")
            )
            assertTrue(!File(homeDirectory, "uploaded.txt").exists())
        }
    }

    private fun waitForState(state: FtpServerService.State) {
        val deadline = SystemClock.uptimeMillis() + STATE_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            var currentState: FtpServerService.State? = null
            instrumentation.runOnMainSync { currentState = FtpServerService.stateLiveData.value }
            if (currentState == state) {
                return
            }
            Thread.sleep(100)
        }
        throw AssertionError("The FTP server never reached $state")
    }

    /** Just enough of an FTP client to see whether the server works. */
    private class FtpClient(private val port: Int) : Closeable {
        private val socket = Socket("127.0.0.1", port)
        private val reader = socket.getInputStream().bufferedReader()
        private val writer = socket.getOutputStream().bufferedWriter()

        val greeting: String = readReply()

        fun login() {
            val userReply = command("USER ${FtpServerService.USERNAME_ANONYMOUS}")
            if (userReply.startsWith("331")) {
                command("PASS test@example.com")
            }
            command("TYPE I")
        }

        fun command(command: String): String {
            writer.write("$command\r\n")
            writer.flush()
            return readReply()
        }

        /** Runs [command] over a passive data connection, returning what came back. */
        fun transfer(command: String, upload: ByteArray? = null): ByteArray? {
            val dataSocket = openPassiveSocket()
            val reply = command(command)
            if (!reply.startsWith("1")) {
                dataSocket.close()
                throw AssertionError("The server refused \"$command\" with $reply")
            }
            val data = dataSocket.use {
                if (upload != null) {
                    it.getOutputStream().apply {
                        write(upload)
                        flush()
                    }
                    it.shutdownOutput()
                    null
                } else {
                    it.getInputStream().readBytes()
                }
            }
            readReply()
            return data
        }

        fun transferReply(command: String, upload: ByteArray): String {
            val dataSocket = openPassiveSocket()
            val reply = command(command)
            dataSocket.close()
            return reply
        }

        private fun openPassiveSocket(): Socket {
            val reply = command("PASV")
            check(reply.startsWith("227")) { "PASV failed: $reply" }
            val numbers = reply.substringAfter('(').substringBefore(')').split(',')
            check(numbers.size == 6) { "Unexpected PASV reply: $reply" }
            val dataPort = numbers[4].trim().toInt() * 256 + numbers[5].trim().toInt()
            return Socket("127.0.0.1", dataPort)
        }

        private fun readReply(): String {
            var line = checkNotNull(reader.readLine()) { "The server closed the connection" }
            if (line.length > 3 && line[3] == '-') {
                val code = line.take(3)
                while (true) {
                    val next = checkNotNull(reader.readLine()) { "Unfinished reply: $line" }
                    if (next.startsWith("$code ")) {
                        line = next
                        break
                    }
                }
            }
            return line
        }

        override fun close() {
            socket.close()
        }
    }

    companion object {
        private const val PORT = 50021
        private const val FILE_CONTENTS = "Hello, FTP!"
        private const val UPLOAD_CONTENTS = "Uploaded"
        private const val STATE_TIMEOUT_MILLIS = 10000L
    }
}
