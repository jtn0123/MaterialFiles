/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The changes this process makes, as a remote folder being looked at hears of them. */
class PathWatchSourceTest {
    private val directory = TestPath("/share/folder")
    private val source = PathWatchSource.inProcess(directory)
    private val watchService = source.newWatchService()
    private val changes = LinkedBlockingQueue<Unit>()
    private val thread = Thread(
        WatchServicePoller(watchService, { source.register(watchService) }, { changes.put(Unit) })
    ).apply { isDaemon = true }

    @After
    fun tearDown() {
        thread.interrupt()
        watchService.close()
        thread.join(TIMEOUT_MILLIS)
    }

    private fun start() {
        source.register(watchService)
        thread.start()
    }

    @Test
    fun aChangeInTheFolderReachesItsObserver() {
        start()

        LocalWatchService.onEntryCreated(directory.resolve("new"))

        assertNotNull(changes.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aChangeToTheFolderItselfReachesItsObserver() {
        start()

        LocalWatchService.onEntryModified(directory)

        assertNotNull(changes.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aChangeElsewhereDoesNot() {
        start()

        LocalWatchService.onEntryDeleted(TestPath("/share/other/file"))
        LocalWatchService.onEntryCreated(directory.resolve("sub").resolve("deeper"))

        assertNull(changes.poll(QUIET_MILLIS, TimeUnit.MILLISECONDS))
    }

    companion object {
        private const val TIMEOUT_MILLIS = 5_000L
        private const val QUIET_MILLIS = 200L
    }
}
