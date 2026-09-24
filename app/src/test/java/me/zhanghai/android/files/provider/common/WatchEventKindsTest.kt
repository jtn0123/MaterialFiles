/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.Path
import java8.nio.file.StandardWatchEventKinds
import java8.nio.file.WatchEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a watch service accepts at registration and which events a poll turns into. */
class WatchEventKindsTest {
    private val create = StandardWatchEventKinds.ENTRY_CREATE
    private val delete = StandardWatchEventKinds.ENTRY_DELETE
    private val modify = StandardWatchEventKinds.ENTRY_MODIFY
    private val allKinds = setOf<WatchEvent.Kind<*>>(create, delete, modify)

    private fun kindsOf(
        vararg kinds: WatchEvent.Kind<*>,
        modifiers: Array<WatchEvent.Modifier> = emptyArray()
    ) = watchEventKindSetOf(arrayOf(*kinds), modifiers)

    @Test
    fun theEntryKindsAreKeptAndOverflowIsDropped() {
        assertEquals(
            allKinds,
            kindsOf(create, delete, modify, StandardWatchEventKinds.OVERFLOW, create)
        )
        assertTrue(kindsOf(StandardWatchEventKinds.OVERFLOW).isEmpty())
    }

    @Test
    fun anUnknownKindIsUnsupported() {
        val kind = object : WatchEvent.Kind<Any> {
            override fun name(): String = "SOMETHING_ELSE"

            override fun type(): Class<Any> = Any::class.java
        }
        val thrown = assertThrows(UnsupportedOperationException::class.java) {
            kindsOf(create, kind)
        }
        assertEquals("SOMETHING_ELSE", thrown.message)
    }

    @Test
    fun anyModifierIsUnsupported() {
        val modifier = WatchEvent.Modifier { "SENSITIVITY_HIGH" }
        val thrown = assertThrows(UnsupportedOperationException::class.java) {
            kindsOf(create, modifiers = arrayOf(modifier))
        }
        assertEquals("SENSITIVITY_HIGH", thrown.message)
    }

    @Test
    fun aPollReportsDeletionsAndChangesBeforeCreations() {
        val kept = TestPath("/dir/kept")
        val changed = TestPath("/dir/changed")
        val deleted = TestPath("/dir/deleted")
        val created = TestPath("/dir/created")
        val oldFiles = mapOf<Path, Int>(kept to 1, changed to 1, deleted to 1)
        val newFiles = mapOf<Path, Int>(created to 1, kept to 1, changed to 2)
        assertEquals(
            listOf(modify to changed, delete to deleted, create to created),
            diffPolledFiles(oldFiles, newFiles, allKinds)
        )
    }

    @Test
    fun aPollOnlyReportsTheKindsThatWereAskedFor() {
        val oldFiles = mapOf<Path, Int>(TestPath("/a") to 1, TestPath("/b") to 1)
        val newFiles = mapOf<Path, Int>(TestPath("/a") to 2, TestPath("/c") to 1)
        assertEquals(
            listOf(create to TestPath("/c")),
            diffPolledFiles(oldFiles, newFiles, setOf(create))
        )
        assertEquals(
            listOf(delete to TestPath("/b")),
            diffPolledFiles(oldFiles, newFiles, setOf(delete))
        )
        assertTrue(diffPolledFiles(oldFiles, oldFiles, allKinds).isEmpty())
    }

    @Test
    fun aLocalChangeReachesTheKeyWatchingItsDirectory() {
        val directory = TestPath("/watched")
        LocalWatchService().use { service ->
            val key = service.register(directory, arrayOf(create, delete))
            LocalWatchService.onEntryCreated(directory.resolve("new"))
            // Not asked for, so nothing is queued for it.
            LocalWatchService.onEntryModified(directory.resolve("new"))
            LocalWatchService.onEntryDeleted(TestPath("/elsewhere/file"))
            assertSame(key, service.poll())
            val events = key.pollEvents()
            assertEquals(1, events.size)
            assertEquals(create, events[0].kind())
            assertEquals(directory.resolve("new"), events[0].context())
            assertTrue(key.reset())
            assertNull(service.poll())
        }
    }

    @Test
    fun registeringAgainUpdatesTheKindsOfTheSameKey() {
        val directory = TestPath("/watched")
        LocalWatchService().use { service ->
            val key = service.register(directory, arrayOf(create))
            assertSame(key, service.register(directory, arrayOf(modify)))
            LocalWatchService.onEntryCreated(directory.resolve("new"))
            assertNull(service.poll())
            LocalWatchService.onEntryModified(directory)
            assertNotNull(service.poll())
            key.pollEvents()
            key.reset()
            key.cancel()
            LocalWatchService.onEntryModified(directory)
            assertNull(service.poll())
        }
    }
}
