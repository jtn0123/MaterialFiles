/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.Path
import java8.nio.file.StandardWatchEventKinds
import java8.nio.file.WatchEvent
import java8.nio.file.WatchService

/**
 * Where [WatchServicePathObservable] takes the changes to a path from: a watch service, and how to
 * register the path with it, which may throw an [java.io.IOException].
 *
 * @param retriesFirstRegistration whether failing to register at first is retried like a lost
 * watch (see [WatchServicePoller]) instead of failing the observable.
 */
class PathWatchSource(
    val newWatchService: () -> WatchService,
    val register: (WatchService) -> Unit,
    val retriesFirstRegistration: Boolean = false
) {
    companion object {
        private val KINDS = arrayOf<WatchEvent.Kind<*>>(
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_DELETE,
            StandardWatchEventKinds.ENTRY_MODIFY
        )

        /** The watch service of the file system of [path]. */
        fun of(path: Path, retriesFirstRegistration: Boolean = false): PathWatchSource =
            PathWatchSource(
                { path.fileSystem.newWatchService() },
                { path.register(it, *KINDS) },
                retriesFirstRegistration
            )

        /** The changes this process makes itself, as the clients tell [LocalWatchService]. */
        fun inProcess(path: Path): PathWatchSource = PathWatchSource(
            { LocalWatchService() },
            { (it as LocalWatchService).register(path, KINDS) }
        )
    }
}
