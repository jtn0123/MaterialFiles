/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import java8.nio.file.ClosedWatchServiceException
import java8.nio.file.NoSuchFileException
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.StandardWatchEventKinds
import java8.nio.file.WatchEvent
import java8.nio.file.WatchKey
import me.zhanghai.android.files.provider.NeverUseRootRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The watch service for providers that have no notifications of their own: it lists the directory
 * again every second and reports whatever changed.
 *
 * Only registration and the life cycle of a key are exercised here, against an empty local
 * directory. Comparing two listings needs file attributes that compare by value, which a debug
 * build asserts; only the network providers have those, and they need a server this test cannot
 * reach.
 */
@RunWith(AndroidJUnit4::class)
class PollingWatchServiceTest {
    @get:Rule
    val neverUseRootRule = NeverUseRootRule()

    private lateinit var directory: File
    private lateinit var root: Path
    private lateinit var watchService: PollingWatchService

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.filesDir, "PollingWatchServiceTest").apply {
            deleteRecursively()
            mkdirs()
        }
        root = Paths.get(directory.path)
        watchService = PollingWatchService()
    }

    @After
    fun tearDown() {
        try {
            watchService.close()
        } catch (e: ClosedWatchServiceException) {
            // Already closed by the test.
        }
        directory.deleteRecursively()
    }

    private fun register(path: Path = root, vararg kinds: WatchEvent.Kind<*>): WatchKey =
        watchService.register(
            path,
            if (kinds.isEmpty()) {
                arrayOf(
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY
                )
            } else {
                arrayOf(*kinds)
            }
        )

    @Test
    fun registeringTheSamePathAgainKeepsTheSameKey() {
        val key = register()

        val sameKey = register(root, StandardWatchEventKinds.ENTRY_CREATE)

        assertSame(key, sameKey)
        assertTrue(key.isValid)
    }

    @Test
    fun aCancelledKeyBecomesInvalidAndStopsReporting() {
        val key = register()

        key.cancel()

        assertFalse(key.isValid)
        File(directory, "created").writeText("content")
        assertEquals(null, watchService.poll(2, TimeUnit.SECONDS))
    }

    @Test
    fun theKeyOfADeletedDirectoryBecomesInvalid() {
        val key = register()

        directory.deleteRecursively()

        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (key.isValid) {
            if (System.currentTimeMillis() >= deadline) {
                fail("The key stayed valid after its directory went away")
            }
            Thread.sleep(POLL_MILLIS)
        }
        // The poller signals the key so that a waiting caller wakes up and sees it invalid.
        assertSame(key, watchService.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        assertFalse(key.reset())
    }

    @Test
    fun registeringAMissingPathFails() {
        try {
            register(root.resolve("missing"))
            fail("expected NoSuchFileException")
        } catch (e: NoSuchFileException) {
            assertEquals(root.resolve("missing").toString(), e.file)
        }
    }

    @Test
    fun anUnsupportedEventKindIsRefused() {
        try {
            register(root, UnsupportedKind)
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            assertEquals("unsupported", e.message)
        }
        // OVERFLOW is accepted and adds nothing of its own.
        assertTrue(
            register(
                root,
                StandardWatchEventKinds.OVERFLOW,
                StandardWatchEventKinds.ENTRY_CREATE
            ).isValid
        )
    }

    @Test
    fun closingStopsEveryPoller() {
        register()

        watchService.close()

        try {
            watchService.poll()
            fail("expected ClosedWatchServiceException")
        } catch (e: ClosedWatchServiceException) {
            // Expected.
        }
    }

    private object UnsupportedKind : WatchEvent.Kind<Any> {
        override fun name(): String = "unsupported"

        override fun type(): Class<Any> = Any::class.java
    }

    companion object {
        private const val TIMEOUT_MILLIS = 15_000L
        private const val POLL_MILLIS = 100L
    }
}
