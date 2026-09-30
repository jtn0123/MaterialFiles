/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java8.nio.file.Path
import me.zhanghai.android.files.provider.common.PathObservable
import me.zhanghai.android.files.provider.common.TestPath
import me.zhanghai.android.files.util.warningLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A listing waits for [PathObserver] to be in place, so that a change between reading the folder
 * and starting to observe it is not lost.
 */
class PathObserverTest {
    private class FakeObservable : PathObservable {
        val observers = mutableListOf<() -> Unit>()

        @Volatile
        var isClosed = false

        override fun addObserver(observer: () -> Unit) {
            observers += observer
        }

        override fun removeObserver(observer: () -> Unit) {
            observers -= observer
        }

        override fun close() {
            isClosed = true
        }

        fun change() {
            observers.forEach { it() }
        }
    }

    private val path = TestPath("/folder")
    private val observable = FakeObservable()
    private val canObserve = CountDownLatch(1)
    private val observedPaths = LinkedBlockingQueue<Path>()
    private val changes = LinkedBlockingQueue<Unit>()
    private val executor = Executor { Thread(it).apply { isDaemon = true }.start() }
    private val warnings = mutableListOf<String>()
    private lateinit var defaultWarningLogger: (String, String, Throwable) -> Unit

    @Before
    fun setUp() {
        defaultWarningLogger = warningLogger
        warningLogger =
            { tag, operation, _ -> synchronized(warnings) { warnings += "$tag: $operation" } }
    }

    @After
    fun tearDown() {
        warningLogger = defaultWarningLogger
    }

    private fun newObserver(
        observe: (Path) -> PathObservable = {
            canObserve.await()
            observedPaths.put(it)
            observable
        }
    ): PathObserver = PathObserver(path, observe, executor, { it() }) { changes.put(Unit) }

    @Test
    fun awaitingReturnsOnceTheObserverIsInPlace() {
        val observer = newObserver()
        val waiter = Thread { observer.awaitObserving(TIMEOUT_MILLIS) }.apply { start() }
        waiter.join(QUIET_MILLIS)
        assertTrue("Returned before the observer was in place", waiter.isAlive)

        canObserve.countDown()

        waiter.join(TIMEOUT_MILLIS)
        assertFalse(waiter.isAlive)
        assertEquals(path, observedPaths.poll())
        observable.change()
        assertNotNull(changes.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aListingThatStopsWaitingIsReloadedOnceTheObserverIsInPlace() {
        val observer = newObserver()

        assertFalse(observer.awaitObserving(1))
        assertNull(changes.poll(QUIET_MILLIS, TimeUnit.MILLISECONDS))
        canObserve.countDown()

        // For what the listing may have missed.
        assertNotNull(changes.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        assertNull(changes.poll(QUIET_MILLIS, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aListingThatWaitedLongEnoughIsNotReloaded() {
        canObserve.countDown()
        val observer = newObserver()

        assertTrue(observer.awaitObserving(TIMEOUT_MILLIS))

        assertNull(changes.poll(QUIET_MILLIS, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aPathThatCannotBeObservedDoesNotHoldUpTheListing() {
        val observer = newObserver { throw IOException("No watch for you") }

        assertTrue(observer.awaitObserving(TIMEOUT_MILLIS))
        assertEquals(
            listOf("PathObserver: Observe /folder for changes"),
            synchronized(warnings) { warnings.toList() }
        )
    }

    @Test
    fun aPathWithoutChangeNotificationDoesNotHoldUpTheListing() {
        val observer = newObserver { throw UnsupportedOperationException() }

        assertTrue(observer.awaitObserving(TIMEOUT_MILLIS))
        assertTrue(synchronized(warnings) { warnings.isEmpty() })
    }

    @Test
    fun anObserverClosedBeforeItStartsNeverObserves() {
        val observers = LinkedBlockingQueue<Runnable>()
        val observer = PathObserver(
            path,
            {
                observedPaths.put(it)
                observable
            },
            { observers.put(it) },
            { it() }
        ) { changes.put(Unit) }

        val start = observers.take()
        observer.close()
        // The background threads can run the closing first.
        observers.take().run()
        start.run()

        assertTrue(observer.awaitObserving(TIMEOUT_MILLIS))
        assertNull(observedPaths.poll())
    }

    @Test
    fun closingTheObserverClosesWhatItObserves() {
        canObserve.countDown()
        val observer = newObserver()
        assertTrue(observer.awaitObserving(TIMEOUT_MILLIS))

        observer.close()

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS)
        while (!observable.isClosed && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(observable.isClosed)
    }

    companion object {
        private const val TIMEOUT_MILLIS = 5_000L
        private const val QUIET_MILLIS = 200L
    }
}
