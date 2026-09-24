/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.linux

import android.system.OsConstants
import android.system.StructPollfd
import java.io.Closeable
import java.io.FileDescriptor
import java.io.IOException
import java.io.InterruptedIOException
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.atomic.AtomicInteger
import java8.nio.file.ClosedWatchServiceException
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.StandardWatchEventKinds
import java8.nio.file.WatchEvent
import java8.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.Continuation
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.runBlocking
import me.zhanghai.android.files.provider.FileSystemProviders
import me.zhanghai.android.files.provider.common.AbstractWatchService
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.watchEventKindSetOf
import me.zhanghai.android.files.provider.linux.syscall.Constants
import me.zhanghai.android.files.provider.linux.syscall.Syscall
import me.zhanghai.android.files.provider.linux.syscall.SyscallException
import me.zhanghai.android.files.util.hasBits
import me.zhanghai.android.files.util.logWarning

internal class LocalLinuxWatchService : AbstractWatchService<LocalLinuxWatchKey>() {
    private val poller = Poller(this)

    init {
        poller.start()
    }

    @Throws(IOException::class)
    fun register(
        path: LinuxPath,
        kinds: Array<WatchEvent.Kind<*>>,
        vararg modifiers: WatchEvent.Modifier
    ): LocalLinuxWatchKey {
        return poller.register(path, watchEventKindSetOf(kinds, modifiers))
    }

    override fun cancel(key: LocalLinuxWatchKey) {
        poller.cancel(key)
    }

    @Throws(IOException::class)
    override fun onClose() {
        poller.close()
    }

    private class Poller(private val watchService: LocalLinuxWatchService) :
        Thread("LocalLinuxWatchService.Poller-${id.getAndIncrement()}"),
        Closeable {
        private val socketFds: Array<FileDescriptor>

        private var inotifyFd: FileDescriptor

        private val keys = mutableMapOf<Int, LocalLinuxWatchKey>()

        private val inotifyBuffer = ByteArray(4 * 1024)

        private val runnables: Queue<() -> Unit> = LinkedList()

        private var isClosed = false

        private val lock = Any()

        init {
            isDaemon = true
            try {
                socketFds = Syscall.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0)
                val flags = Syscall.fcntl(socketFds[0], OsConstants.F_GETFL)
                if (!flags.hasBits(OsConstants.O_NONBLOCK)) {
                    Syscall.fcntl(
                        socketFds[0],
                        OsConstants.F_SETFL,
                        flags or OsConstants.O_NONBLOCK
                    )
                }
                inotifyFd = Syscall.inotify_init1(OsConstants.O_NONBLOCK)
            } catch (e: SyscallException) {
                throw e.toFileSystemException(null)
            }
        }

        @Throws(IOException::class)
        fun register(path: LinuxPath, kinds: Set<WatchEvent.Kind<*>>): LocalLinuxWatchKey =
            postAndWait(true) {
                val pathBytes = path.toByteString()
                var mask = eventKindsToMask(kinds)
                mask = maybeAddDontFollowMask(path, mask)
                val wd = try {
                    Syscall.inotify_add_watch(inotifyFd, pathBytes, mask)
                } catch (e: SyscallException) {
                    throw e.toFileSystemException(pathBytes.toString())
                }
                LocalLinuxWatchKey(watchService, path, wd).also { keys[wd] = it }
            }

        private fun maybeAddDontFollowMask(path: Path, mask: Int): Int {
            val attributes = try {
                path.readAttributes(BasicFileAttributes::class.java)
            } catch (ignored: IOException) {
                try {
                    path.readAttributes(BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (ignored: IOException) {
                    null
                }
            }
            return if (attributes != null && attributes.isSymbolicLink) {
                mask or Constants.IN_DONT_FOLLOW
            } else {
                mask
            }
        }

        fun cancel(key: LocalLinuxWatchKey) {
            try {
                postAndWait(true) { removeWatch(key) }
            } catch (e: Exception) {
                e.logWarning("LocalLinuxWatchService", "cancel")
            }
        }

        private fun removeWatch(key: LocalLinuxWatchKey) {
            if (!key.isValid) {
                return
            }
            val wd = key.watchDescriptor
            try {
                Syscall.inotify_rm_watch(inotifyFd, wd)
            } catch (e: SyscallException) {
                e.toFileSystemException(key.watchable().toString())
                    .logWarning("LocalLinuxWatchService", "cancel")
            }
            key.setInvalid()
            keys.remove(wd)
        }

        @Throws(IOException::class)
        override fun close() {
            postAndWait(false) { closeOnPollerThread() }
        }

        @Throws(IOException::class)
        private fun closeOnPollerThread() {
            for (key in keys.values) {
                try {
                    Syscall.inotify_rm_watch(inotifyFd, key.watchDescriptor)
                } catch (e: SyscallException) {
                    throw e.toFileSystemException(key.watchable().toString())
                }
                key.setInvalid()
            }
            keys.clear()
            try {
                Syscall.close(inotifyFd)
                Syscall.close(socketFds[1])
                Syscall.close(socketFds[0])
            } catch (e: SyscallException) {
                e.logWarning("LocalLinuxWatchService", "close")
            }
            isClosed = true
        }

        /**
         * Runs [block] on the poller thread and waits for its result. [block] may throw an
         * [IOException] or a [RuntimeException] to fail the call.
         */
        @Throws(IOException::class)
        private fun <T> postAndWait(ensureOpen: Boolean, block: () -> T): T = try {
            runBlocking {
                suspendCoroutine { continuation ->
                    post(ensureOpen, continuation) {
                        val result = try {
                            Result.success(block())
                        } catch (e: IOException) {
                            Result.failure(e)
                        } catch (e: RuntimeException) {
                            Result.failure(e)
                        }
                        continuation.resumeWith(result)
                    }
                }
            }
        } catch (e: InterruptedException) {
            throw InterruptedIOException().apply { initCause(e) }
        }

        private fun post(ensureOpen: Boolean, continuation: Continuation<*>, runnable: () -> Unit) {
            synchronized(lock) {
                if (isClosed && ensureOpen) {
                    // The poller has stopped and its file descriptors are gone, so the write
                    // below would only fail with EBADF.
                    continuation.resumeWithException(ClosedWatchServiceException())
                    return
                }
                runnables.offer {
                    if (isClosed) {
                        if (ensureOpen) {
                            continuation.resumeWithException(ClosedWatchServiceException())
                        }
                        return@offer
                    }
                    runnable()
                }
            }
            try {
                Syscall.write(socketFds[1], ONE_BYTE)
            } catch (e: InterruptedIOException) {
                continuation.resumeWithException(e)
            } catch (e: SyscallException) {
                continuation.resumeWithException(e.toFileSystemException(null))
            }
        }

        override fun run() {
            val fds = arrayOf(createStructPollFd(socketFds[0]), createStructPollFd(inotifyFd))
            try {
                while (true) {
                    fds[0].revents = 0
                    fds[1].revents = 0
                    Syscall.poll(fds, -1)
                    if (fds[0].hasInput() && readOrZero(socketFds[0], ONE_BYTE) > 0) {
                        runPostedRunnables()
                        if (isClosed) {
                            break
                        }
                    }
                    if (fds[1].hasInput()) {
                        val size = readOrZero(inotifyFd, inotifyBuffer)
                        if (size > 0) {
                            dispatchInotifyEvents(size)
                        }
                    }
                }
            } catch (e: InterruptedIOException) {
                e.logWarning("LocalLinuxWatchService", "run")
            } catch (e: SyscallException) {
                e.logWarning("LocalLinuxWatchService", "run")
            }
        }

        private fun StructPollfd.hasInput(): Boolean = revents.toInt().hasBits(OsConstants.POLLIN)

        // Both file descriptors are non-blocking, so a read with nothing to read yet is 0 bytes.
        @Throws(InterruptedIOException::class, SyscallException::class)
        private fun readOrZero(fd: FileDescriptor, buffer: ByteArray): Int = try {
            Syscall.read(fd, buffer)
        } catch (e: SyscallException) {
            if (e.errno != OsConstants.EAGAIN) {
                throw e
            }
            0
        }

        private fun runPostedRunnables() {
            synchronized(lock) {
                while (true) {
                    val runnable = runnables.poll() ?: break
                    runnable()
                }
            }
        }

        private fun dispatchInotifyEvents(size: Int) {
            if (FileSystemProviders.overflowWatchEvents) {
                addOverflowToAllKeys()
                return
            }
            val events = Syscall.inotify_get_events(inotifyBuffer, 0, size)
            for (event in events) {
                if (event.mask.hasBits(Constants.IN_Q_OVERFLOW)) {
                    addOverflowToAllKeys()
                    break
                }
                // An event can still arrive for a watch that was just removed.
                val key = keys[event.wd] ?: continue
                if (event.mask.hasBits(Constants.IN_IGNORED)) {
                    key.setInvalid()
                    key.signal()
                    keys.remove(event.wd)
                } else {
                    val kind = maskToEventKind(event.mask)
                    val name = event.name?.let { key.watchable().fileSystem.getPath(it) }
                    key.addEvent(kind, name)
                }
            }
        }

        private fun addOverflowToAllKeys() {
            for (key in keys.values) {
                key.addEvent(StandardWatchEventKinds.OVERFLOW, null)
            }
        }

        private fun createStructPollFd(fd: FileDescriptor): StructPollfd = StructPollfd().apply {
            this.fd = fd
            events = OsConstants.POLLIN.toShort()
        }

        private fun eventKindsToMask(kinds: Set<WatchEvent.Kind<*>>): Int {
            var mask = 0
            for (kind in kinds) {
                when (kind) {
                    StandardWatchEventKinds.ENTRY_CREATE ->
                        mask = mask or (Constants.IN_CREATE or Constants.IN_MOVED_TO)

                    StandardWatchEventKinds.ENTRY_DELETE ->
                        mask = mask or (
                            Constants.IN_DELETE_SELF or Constants.IN_DELETE
                                or Constants.IN_MOVED_FROM
                            )

                    StandardWatchEventKinds.ENTRY_MODIFY ->
                        mask = mask or (
                            Constants.IN_MOVE_SELF or Constants.IN_MODIFY
                                or Constants.IN_ATTRIB
                            )
                }
            }
            return mask
        }

        private fun maskToEventKind(mask: Int): WatchEvent.Kind<Path> = when {
            mask.hasBits(Constants.IN_CREATE) || mask.hasBits(Constants.IN_MOVED_TO) ->
                StandardWatchEventKinds.ENTRY_CREATE

            mask.hasBits(Constants.IN_DELETE_SELF) || mask.hasBits(Constants.IN_DELETE) ||
                mask.hasBits(Constants.IN_MOVED_FROM) ->
                StandardWatchEventKinds.ENTRY_DELETE

            mask.hasBits(Constants.IN_MOVE_SELF) || mask.hasBits(Constants.IN_MODIFY) ||
                mask.hasBits(Constants.IN_ATTRIB) -> StandardWatchEventKinds.ENTRY_MODIFY

            else -> throw AssertionError(mask)
        }

        companion object {
            private val ONE_BYTE = ByteArray(1)

            private val id = AtomicInteger()
        }
    }
}
