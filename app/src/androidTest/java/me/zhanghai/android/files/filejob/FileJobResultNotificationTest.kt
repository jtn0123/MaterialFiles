/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java8.nio.file.Paths
import me.zhanghai.android.files.UiFailureDiagnosticsRule
import me.zhanghai.android.files.filelist.FileListActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A copy that finishes leaves a notification saying what went where, which opens the folder it
 * went to, instead of ending silently.
 */
@RunWith(AndroidJUnit4::class)
class FileJobResultNotificationTest {
    @get:Rule
    val diagnostics = UiFailureDiagnosticsRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private lateinit var directory: File
    private lateinit var sourceDirectory: File
    private lateinit var targetDirectory: File
    private var scenario: ActivityScenario<FileListActivity>? = null

    @Before
    fun setUp() {
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        directory = File(context.filesDir, "result-notification-${UUID.randomUUID()}")
        sourceDirectory = File(directory, "source").apply { mkdirs() }
        targetDirectory = File(directory, "Target").apply { mkdirs() }
        notificationManager.cancelAll()
    }

    @After
    fun tearDown() {
        scenario?.close()
        notificationManager.cancelAll()
        directory.deleteRecursively()
    }

    @Test
    fun aFinishedCopySaysWhereItWent() {
        val source = File(sourceDirectory, "Report.txt").apply { writeText("report") }
        startInForeground()

        instrumentation.runOnMainSync {
            FileJobService.copy(
                listOf(Paths.get(source.path)),
                Paths.get(targetDirectory.path),
                context
            )
        }

        val notification = awaitNotification("Copied “Report.txt” to “Target”")
        assertEquals("report", File(targetDirectory, "Report.txt").readText())
        assertNotNull("The notification does not open the folder", notification.contentIntent)
        assertEquals(
            Notification.FLAG_AUTO_CANCEL,
            notification.flags and Notification.FLAG_AUTO_CANCEL
        )
        assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
    }

    @Test
    fun aFinishedCopyOfSeveralFilesCountsThem() {
        val sources = listOf("One.txt", "Two.txt", "Three.txt").map {
            File(sourceDirectory, it).apply { writeText(it) }
        }
        startInForeground()

        instrumentation.runOnMainSync {
            FileJobService.copy(
                sources.map { Paths.get(it.path) },
                Paths.get(targetDirectory.path),
                context
            )
        }

        awaitNotification("Copied 3 items to “Target”")
    }

    /** Jobs are started from the app while it is showing, as they are when the user pastes. */
    private fun startInForeground() {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(targetDirectory), "inode/directory")
            .setClass(context, FileListActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        scenario = ActivityScenario.launch(intent)
    }

    private fun awaitNotification(title: String): Notification {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            notificationManager.activeNotifications
                .map { it.notification }
                .find { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title }
                ?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        val titles = notificationManager.activeNotifications.map {
            it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)
        }
        throw AssertionError("No notification titled \"$title\"; showing $titles")
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { String(it.readBytes()) }
        }

    companion object {
        private const val TIMEOUT_MILLIS = 30_000L
        private const val POLL_MILLIS = 100L
    }
}
