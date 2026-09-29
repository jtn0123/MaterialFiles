/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java8.nio.file.Paths
import me.zhanghai.android.files.UiFailureDiagnosticsRule
import me.zhanghai.android.files.coil.TestJpeg
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
 * Deleting a photo from the viewer is a network round trip on a share, so it must happen off the
 * main thread: the viewer stays responsive while the server takes its time, and the page goes
 * away only once the file is gone.
 */
@RunWith(AndroidJUnit4::class)
class ImageViewerDeleteTest {
    @get:Rule
    val diagnostics = UiFailureDiagnosticsRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private lateinit var directory: File
    private var scenario: ActivityScenario<ImageViewerActivity>? = null
    private val defaultDeleter = ViewerFileDeletion.deleter

    @Before
    fun setUp() {
        directory = File(context.filesDir, "image-delete-${UUID.randomUUID()}").apply { mkdirs() }
        for (name in PHOTO_NAMES) {
            TestJpeg.write(File(directory, name), 320, 240)
        }
    }

    @After
    fun tearDown() {
        ViewerFileDeletion.deleter = defaultDeleter
        scenario?.close()
        directory.deleteRecursively()
    }

    @Test
    fun aSlowDeleteKeepsTheViewerResponsive() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        var deletedOnMainThread: Boolean? = null
        ViewerFileDeletion.deleter = { path ->
            deletedOnMainThread = Looper.getMainLooper().isCurrentThread
            started.countDown()
            // Like a share that is slow to answer.
            release.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            defaultDeleter(path)
        }
        val paths = PHOTO_NAMES.map { Paths.get(File(directory, it).path) }
        val intent = Intent(context, ImageViewerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .also { ImageViewerActivity.putExtras(it, paths, 0) }
        scenario = ActivityScenario.launch(intent)
        assertNotNull(
            "Expected 1/3 in the title",
            device.wait(Until.findObject(By.text("1/3")), TIMEOUT_MILLIS)
        )

        device.wait(Until.findObject(By.desc("More options")), TIMEOUT_MILLIS).click()
        device.wait(Until.findObject(By.text("Delete")), TIMEOUT_MILLIS).click()
        device.wait(Until.findObject(By.text("OK")), TIMEOUT_MILLIS).click()
        assertTrue(
            "The delete never started",
            started.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        )

        // While the delete is still waiting on the server, the main thread keeps running.
        val mainThreadRan = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { mainThreadRan.countDown() }
        assertTrue(
            "The main thread was blocked by the delete",
            mainThreadRan.await(RESPONSIVE_MILLIS, TimeUnit.MILLISECONDS)
        )
        assertEquals(false, deletedOnMainThread)
        assertNotNull(
            "The page went away before the delete finished",
            device.findObject(By.text("1/3"))
        )

        release.countDown()
        assertNotNull(
            "Expected 1/2 once the delete finished",
            device.wait(Until.findObject(By.text("1/2")), TIMEOUT_MILLIS)
        )
        assertFalse(File(directory, PHOTO_NAMES[0]).exists())
    }

    companion object {
        private val PHOTO_NAMES = listOf("Photo1.jpg", "Photo2.jpg", "Photo3.jpg")
        private const val TIMEOUT_MILLIS = 30_000L
        private const val RESPONSIVE_MILLIS = 2_000L
    }
}
