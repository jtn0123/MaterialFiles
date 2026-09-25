/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.system.Os
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java8.nio.file.Paths
import me.zhanghai.android.files.UiFailureDiagnosticsRule
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.provider.NeverUseRootRule
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setting a mode recursively with an uppercase X: the directory gets the mode as asked, and a file
 * inside only keeps execute for the classes that could already execute it.
 */
@RunWith(AndroidJUnit4::class)
class SetFileModeJobTest {
    @get:Rule
    val diagnostics = UiFailureDiagnosticsRule()

    @get:Rule
    val neverUseRootRule = NeverUseRootRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext

    // The shared storage ignores modes, the app's own data directory does not.
    private val directory = File(context.filesDir, "SetFileModeJobTest")
    private val shownDirectory =
        File(Environment.getExternalStorageDirectory(), "Download/SetFileModeJobTest")
    private var scenario: ActivityScenario<FileListActivity>? = null

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        directory.deleteRecursively()
        directory.mkdirs()
        Os.chmod(directory.path, "700".toInt(8))
        File(directory, PLAIN_NAME).writeText("plain")
        Os.chmod(File(directory, PLAIN_NAME).path, "600".toInt(8))
        File(directory, SCRIPT_NAME).writeText("script")
        Os.chmod(File(directory, SCRIPT_NAME).path, "700".toInt(8))
        shownDirectory.mkdirs()
    }

    @After
    fun tearDown() {
        scenario?.close()
        directory.deleteRecursively()
        shownDirectory.deleteRecursively()
    }

    @Test
    fun anUppercaseXOnlyKeepsExecuteWhereAFileAlreadyHadIt() {
        showAnyDirectory()

        instrumentation.runOnMainSync {
            FileJobService.setMode(
                Paths.get(directory.path),
                setOf(
                    PosixFileModeBit.OWNER_READ,
                    PosixFileModeBit.OWNER_WRITE,
                    PosixFileModeBit.OWNER_EXECUTE,
                    PosixFileModeBit.GROUP_READ,
                    PosixFileModeBit.GROUP_EXECUTE,
                    PosixFileModeBit.OTHERS_READ,
                    PosixFileModeBit.OTHERS_EXECUTE
                ),
                true,
                true,
                context
            )
        }

        val expected = mapOf(
            directory to "755",
            File(directory, PLAIN_NAME) to "644",
            File(directory, SCRIPT_NAME) to "744"
        )
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        var actual = modesOf(expected.keys)
        while (actual != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MILLIS)
            actual = modesOf(expected.keys)
        }
        assertEquals(expected, actual)
    }

    /** A job is only started while the app is in the foreground. */
    private fun showAnyDirectory() {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(shownDirectory), "inode/directory")
            .setClass(context, FileListActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        scenario = ActivityScenario.launch(intent)
        assertNotNull(
            "The file list never appeared",
            device.wait(Until.findObject(By.text(shownDirectory.name)), TIMEOUT_MILLIS)
        )
    }

    private fun modesOf(files: Collection<File>): Map<File, String> =
        files.associateWith { Integer.toOctalString(Os.stat(it.path).st_mode and "777".toInt(8)) }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { String(it.readBytes()) }
        }

    companion object {
        private const val PLAIN_NAME = "plain.txt"
        private const val SCRIPT_NAME = "script.sh"
        private const val TIMEOUT_MILLIS = 30_000L
        private const val POLL_MILLIS = 100L
    }
}
