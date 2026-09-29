/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java8.nio.file.ClosedWatchServiceException
import java8.nio.file.NoSuchFileException
import java8.nio.file.StandardWatchEventKinds
import me.zhanghai.android.files.util.warningLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** How [WatchServicePoller] recovers a watch whose key went invalid, and how it stops. */
class WatchServicePollerTest {
    private class FakeKey(service: FakeWatchService, path: TestPath) :
        AbstractWatchKey<FakeKey, TestPath>(service, path)

    private class FakeWatchService : AbstractWatchService<FakeKey>() {
        override fun cancel(key: FakeKey) {
            key.setInvalid()
        }

        override fun onClose() {}
    }

    private val path = TestPath("/watched")
    private val service = FakeWatchService()
    private val changes = LinkedBlockingQueue<Unit>()
    private val delays = LinkedBlockingQueue<Long>()
    private val warnings = mutableListOf<String>()
    private lateinit var defaultWarningLogger: (String, String, Throwable) -> Unit

    @Volatile
    private var key: FakeKey? = null

    @Volatile
    private var failuresBeforeRegistering = 0

    @Volatile
    private var registrations = 0

    @Volatile
    private var nowMillis = 0L

    private var thread: Thread? = null

    @Before
    fun setUp() {
        defaultWarningLogger = warningLogger
        warningLogger =
            { tag, operation, _ -> synchronized(warnings) { warnings += "$tag: $operation" } }
    }

    @After
    fun tearDown() {
        thread?.interrupt()
        service.close()
        thread?.join(TIMEOUT_MILLIS)
        warningLogger = defaultWarningLogger
    }

    private fun register() {
        if (failuresBeforeRegistering > 0) {
            --failuresBeforeRegistering
            throw NoSuchFileException(path.toString())
        }
        ++registrations
        key = FakeKey(service, path)
    }

    private fun start(
        isRegistered: Boolean = true,
        sleep: (Long) -> Unit = { delays.put(it) }
    ): Thread {
        if (isRegistered) {
            register()
        }
        val poller = WatchServicePoller(
            service,
            ::register,
            { changes.put(Unit) },
            isRegistered,
            sleep
        ) { nowMillis }
        return Thread(poller).apply {
            isDaemon = true
            start()
            thread = this
        }
    }

    private fun loseKey() {
        val key = key!!
        key.setInvalid()
        key.signal()
    }

    private fun awaitChange() {
        assertNotNull("No change reported", changes.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
    }

    private fun assertNoMoreChanges() {
        assertNull(changes.poll(QUIET_MILLIS, TimeUnit.MILLISECONDS))
    }

    private fun awaitDelays(count: Int): List<Long> =
        List(count) { delays.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)!! }

    @Test
    fun anEventIsAChange() {
        start()

        key!!.addEvent(StandardWatchEventKinds.ENTRY_CREATE, TestPath("new"))

        awaitChange()
        assertNoMoreChanges()
    }

    @Test
    fun aLostKeyIsAChangeAndTheWatchComesBackAfterAGrowingWait() {
        start()
        failuresBeforeRegistering = 5

        loseKey()

        awaitChange()
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), awaitDelays(6))
        // Registered again: what changed while nothing was watching gets loaded.
        awaitChange()
        assertEquals(2, registrations)
        assertNoMoreChanges()
        // And the new key is watched like the first one.
        key!!.addEvent(StandardWatchEventKinds.ENTRY_DELETE, TestPath("old"))
        awaitChange()
    }

    @Test
    fun aFailingRegistrationIsLoggedOncePerOutage() {
        start()
        failuresBeforeRegistering = 3

        loseKey()
        awaitChange()
        awaitChange()

        assertEquals(
            listOf("WatchServicePoller: Watch for changes again"),
            synchronized(warnings) {
                warnings.toList()
            }
        )
    }

    @Test
    fun aKeyLostSoonAgainWaitsLongerAndOneThatLastedStartsOverFromTheShortestWait() {
        start()

        loseKey()
        awaitChange()
        assertEquals(listOf(5_000L), awaitDelays(1))
        awaitChange()

        // Lost again right away, as when the server takes the watch and then fails it.
        loseKey()
        awaitChange()
        assertEquals(listOf(10_000L), awaitDelays(1))
        awaitChange()

        // This one lasted a minute, so it was working.
        nowMillis += WatchServicePoller.MAX_RETRY_DELAY_MILLIS
        loseKey()
        awaitChange()
        assertEquals(listOf(5_000L), awaitDelays(1))
        awaitChange()
    }

    @Test
    fun aWatchThatCouldNotBeRegisteredAtFirstIsRegisteredLater() {
        failuresBeforeRegistering = 1
        start(isRegistered = false)

        assertEquals(listOf(5_000L, 10_000L), awaitDelays(2))
        awaitChange()
        assertEquals(1, registrations)
        assertNoMoreChanges()
    }

    @Test
    fun interruptingThePollerWhileItWaitsToRegisterStopsIt() {
        val thread = start { Thread.sleep(it) }

        loseKey()
        awaitChange()
        thread.interrupt()

        thread.join(TIMEOUT_MILLIS)
        assertFalse(thread.isAlive)
        assertEquals(1, registrations)
    }

    @Test
    fun closingTheWatchServiceStopsThePoller() {
        val thread = start()

        service.close()

        thread.join(TIMEOUT_MILLIS)
        assertFalse(thread.isAlive)
        assertNoMoreChanges()
    }

    @Test
    fun aWatchServiceClosedWhileRegisteringStopsThePoller() {
        val registerOnClosed = {
            throw ClosedWatchServiceException()
        }
        val thread = Thread(
            WatchServicePoller(
                service,
                registerOnClosed,
                { changes.put(Unit) },
                isRegistered = false,
                sleep = {}
            )
        ).apply { start() }

        thread.join(TIMEOUT_MILLIS)

        assertFalse(thread.isAlive)
        assertNoMoreChanges()
    }

    @Test
    fun anIOExceptionOtherThanAMissingFileIsRetriedToo() {
        var failed = false
        val thread = Thread(
            WatchServicePoller(
                service,
                {
                    if (!failed) {
                        failed = true
                        throw IOException("Connection reset")
                    }
                    ++registrations
                },
                { changes.put(Unit) },
                isRegistered = false,
                sleep = { delays.put(it) }
            )
        ).apply { start() }
        this.thread = thread

        assertEquals(listOf(5_000L, 10_000L), awaitDelays(2))
        awaitChange()
        assertEquals(1, registrations)
    }

    companion object {
        private const val TIMEOUT_MILLIS = 5_000L
        private const val QUIET_MILLIS = 200L
    }
}
