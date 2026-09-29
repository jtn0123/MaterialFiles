/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java8.nio.file.Path
import java8.nio.file.StandardWatchEventKinds
import java8.nio.file.WatchService

class WatchServicePathObservable(path: Path, intervalMillis: Long) :
    AbstractPathObservable(
        intervalMillis
    ) {
    private val watchService: WatchService
    private val poller: Thread

    init {
        var watchService: WatchService? = null
        var poller: Thread? = null
        var successful = false
        try {
            watchService = path.fileSystem.newWatchService()
            this.watchService = watchService
            val register = {
                path.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY
                )
                Unit
            }
            register()
            poller = Thread(
                WatchServicePoller(watchService, register, { notifyObservers() }),
                "WatchServicePathObservable.Poller-${pollerId.getAndIncrement()}"
            ).apply { isDaemon = true }
            this.poller = poller
            poller.start()
            successful = true
        } finally {
            if (!successful) {
                poller?.interrupt()
                watchService?.close()
            }
        }
    }

    @Throws(IOException::class)
    override fun onCloseLocked() {
        poller.interrupt()
        watchService.close()
    }

    companion object {
        private val pollerId = AtomicInteger()
    }
}
