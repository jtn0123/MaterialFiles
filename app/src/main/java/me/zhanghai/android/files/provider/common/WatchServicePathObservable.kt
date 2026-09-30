/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java8.nio.file.Path
import java8.nio.file.WatchService
import me.zhanghai.android.files.util.closeSafe
import me.zhanghai.android.files.util.logWarning

/**
 * Changes to a path from one or more [PathWatchSource]s, each polled on its own thread and all
 * reported to the same observers.
 */
class WatchServicePathObservable(sources: List<PathWatchSource>, intervalMillis: Long) :
    AbstractPathObservable(
        intervalMillis
    ) {
    private val watchServices = mutableListOf<WatchService>()
    private val pollers = mutableListOf<Thread>()

    constructor(path: Path, intervalMillis: Long) : this(
        listOf(PathWatchSource.of(path)),
        intervalMillis
    )

    init {
        var successful = false
        try {
            for (source in sources) {
                val watchService = source.newWatchService()
                watchServices += watchService
                val register = { source.register(watchService) }
                val isRegistered = try {
                    register()
                    true
                } catch (e: IOException) {
                    if (!source.retriesFirstRegistration) {
                        throw e
                    }
                    e.logWarning("WatchServicePathObservable", "Watch for changes")
                    false
                }
                val poller = Thread(
                    WatchServicePoller(
                        watchService,
                        register,
                        { notifyObservers() },
                        isRegistered
                    ),
                    "WatchServicePathObservable.Poller-${pollerId.getAndIncrement()}"
                )
                poller.isDaemon = true
                pollers += poller
                poller.start()
            }
            successful = true
        } finally {
            if (!successful) {
                pollers.forEach { it.interrupt() }
                watchServices.forEach { it.closeSafe() }
            }
        }
    }

    @Throws(IOException::class)
    override fun onCloseLocked() {
        pollers.forEach { it.interrupt() }
        var exception: IOException? = null
        for (watchService in watchServices) {
            try {
                watchService.close()
            } catch (e: IOException) {
                exception?.addSuppressed(e) ?: run { exception = e }
            }
        }
        exception?.let { throw it }
    }

    companion object {
        private val pollerId = AtomicInteger()
    }
}
