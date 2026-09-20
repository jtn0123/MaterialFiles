/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.linux

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
import java8.nio.file.WatchService
import me.zhanghai.android.files.provider.FileSystemProviders
import me.zhanghai.android.files.provider.NeverUseRootRule
import me.zhanghai.android.files.provider.common.createDirectory
import me.zhanghai.android.files.provider.common.createSymbolicLink
import me.zhanghai.android.files.provider.common.observe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The inotify-backed watch service: it only ever reports what the kernel tells it, so it needs a
 * real filesystem.
 */
@RunWith(AndroidJUnit4::class)
class LinuxWatchServiceTest {
    @get:Rule
    val neverUseRootRule = NeverUseRootRule()

    private lateinit var directory: File
    private lateinit var root: Path
    private lateinit var watchService: WatchService

    private var overflowWatchEvents = false

    @Before
    fun setUp() {
        // The app itself turns every event into an overflow, but the events are what the service
        // is about, so the tests below look at them directly; one test puts the flag back.
        overflowWatchEvents = FileSystemProviders.overflowWatchEvents
        FileSystemProviders.overflowWatchEvents = false
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.filesDir, "LinuxWatchServiceTest").apply {
            deleteRecursively()
            mkdirs()
        }
        root = Paths.get(directory.path)
        watchService = root.fileSystem.newWatchService()
    }

    @After
    fun tearDown() {
        try {
            watchService.close()
        } catch (e: ClosedWatchServiceException) {
            // Already closed by the test.
        }
        FileSystemProviders.overflowWatchEvents = overflowWatchEvents
        directory.deleteRecursively()
    }

    private fun register(path: Path = root): WatchKey = path.register(
        watchService,
        arrayOf(
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_DELETE,
            StandardWatchEventKinds.ENTRY_MODIFY
        )
    )

    /**
     * The events seen so far, including the ones the kernel adds around the interesting one and
     * the ones a single key hands out together.
     */
    private val seenEvents = mutableListOf<String>()

    /** Waits for an event of [kind] naming [name]. */
    private fun awaitEvent(kind: WatchEvent.Kind<*>, name: String) {
        val wanted = "${kind.name()} $name"
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            if (wanted in seenEvents) {
                return
            }
            if (System.currentTimeMillis() >= deadline) {
                fail("no $kind for $name, saw $seenEvents")
            }
            val key = watchService.poll(1, TimeUnit.SECONDS) ?: continue
            key.pollEvents().mapTo(seenEvents) { "${it.kind().name()} ${it.context()}" }
            key.reset()
        }
    }

    @Test
    fun reportsACreatedFile() {
        register()

        File(directory, "created").writeText("content")

        awaitEvent(StandardWatchEventKinds.ENTRY_CREATE, "created")
    }

    @Test
    fun reportsAModifiedFile() {
        File(directory, "file").writeText("content")
        register()

        File(directory, "file").writeText("other content")

        awaitEvent(StandardWatchEventKinds.ENTRY_MODIFY, "file")
    }

    @Test
    fun reportsADeletedFile() {
        File(directory, "file").writeText("content")
        register()

        File(directory, "file").delete()

        awaitEvent(StandardWatchEventKinds.ENTRY_DELETE, "file")
    }

    @Test
    fun reportsARenameAsADeleteAndACreate() {
        File(directory, "before").writeText("content")
        register()

        File(directory, "before").renameTo(File(directory, "after"))

        awaitEvent(StandardWatchEventKinds.ENTRY_DELETE, "before")
        awaitEvent(StandardWatchEventKinds.ENTRY_CREATE, "after")
    }

    @Test
    fun aCancelledKeyBecomesInvalidAndStopsReporting() {
        val key = register()
        assertTrue(key.isValid)

        key.cancel()

        assertFalse(key.isValid)
        File(directory, "created").writeText("content")
        assertEquals(null, watchService.poll(1, TimeUnit.SECONDS))
    }

    @Test
    fun watchingASymbolicLinkWatchesWhatItPointsAt() {
        root.resolve("sub").createDirectory()
        val link = root.resolve("link").createSymbolicLink(Paths.get("sub"))

        val key = link.register(watchService, arrayOf(StandardWatchEventKinds.ENTRY_CREATE))

        assertTrue(key.isValid)
        File(directory, "sub/created").writeText("content")
        awaitEvent(StandardWatchEventKinds.ENTRY_CREATE, "created")
    }

    /** A link that resolves to nothing is watched as the link itself, with `IN_DONT_FOLLOW`. */
    @Test
    fun watchingABrokenSymbolicLinkWatchesTheLink() {
        val link = root.resolve("link").createSymbolicLink(Paths.get("missing"))

        val key = link.register(watchService, arrayOf(StandardWatchEventKinds.ENTRY_DELETE))

        assertTrue(key.isValid)
        assertEquals(link, key.watchable())
    }

    @Test
    fun registeringAMissingDirectoryFails() {
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
            root.register(watchService, arrayOf(UnsupportedKind))
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            assertEquals("unsupported", e.message)
        }
        // OVERFLOW is accepted and contributes nothing to what inotify is asked for.
        val key = root.register(
            watchService,
            arrayOf(StandardWatchEventKinds.OVERFLOW, StandardWatchEventKinds.ENTRY_CREATE)
        )
        assertTrue(key.isValid)
        File(directory, "created").writeText("content")
        awaitEvent(StandardWatchEventKinds.ENTRY_CREATE, "created")
    }

    @Test
    fun overflowWatchEventsReplacesEveryEventWithAnOverflow() {
        FileSystemProviders.overflowWatchEvents = true
        register()

        File(directory, "created").writeText("content")

        val key = watchService.poll(10, TimeUnit.SECONDS)
        assertEquals(root, key!!.watchable())
        val events = key.pollEvents()
        assertEquals(1, events.size)
        assertEquals(StandardWatchEventKinds.OVERFLOW, events[0].kind())
        assertEquals(null, events[0].context())
        assertTrue(key.reset())
    }

    @Test
    fun registeringOnAClosedServiceFails() {
        watchService.close()

        try {
            register()
            fail("expected ClosedWatchServiceException")
        } catch (e: ClosedWatchServiceException) {
            // Expected.
        }
        try {
            watchService.poll()
            fail("expected ClosedWatchServiceException")
        } catch (e: ClosedWatchServiceException) {
            // Expected.
        }
    }

    @Test
    fun observingAPathCallsBackOnEveryChange() {
        var changes = 0
        val observable = root.observe(0)
        try {
            observable.addObserver { synchronized(observable) { changes++ } }

            File(directory, "created").writeText("content")

            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline &&
                synchronized(observable) { changes } == 0
            ) {
                Thread.sleep(50)
            }
            assertTrue(synchronized(observable) { changes } > 0)
        } finally {
            observable.close()
        }
    }

    private object UnsupportedKind : WatchEvent.Kind<Any> {
        override fun name(): String = "unsupported"

        override fun type(): Class<Any> = Any::class.java
    }
}
