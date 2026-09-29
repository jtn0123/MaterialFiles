/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java8.nio.file.DirectoryStream
import java8.nio.file.Path
import java8.nio.file.StandardCopyOption
import java8.nio.file.StandardOpenOption
import java8.nio.file.StandardWatchEventKinds
import java8.nio.file.WatchEvent
import java8.nio.file.WatchService
import java8.nio.file.attribute.BasicFileAttributeView
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.common.LocalWatchService
import me.zhanghai.android.files.provider.common.PathWatchSource
import me.zhanghai.android.files.provider.common.WatchServicePoller
import me.zhanghai.android.files.provider.smb.client.Authenticator
import me.zhanghai.android.files.provider.smb.client.Authority
import me.zhanghai.android.files.provider.smb.client.Client
import me.zhanghai.android.files.util.warningLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test

/**
 * What an SMB folder being looked at learns of changes, against the Samba server
 * `tools/network-tests.py` provisions: the changes this app makes itself, whether or not the
 * server's own watch is alive, and the server's watch coming back after the server dropped it.
 */
class SmbChangeNotificationTest {
    private lateinit var fileSystem: SmbFileSystem
    private val watchServices = mutableListOf<WatchService>()
    private val threads = mutableListOf<Thread>()
    private lateinit var defaultWarningLogger: (String, String, Throwable) -> Unit

    @Before
    fun setUp() {
        val port = System.getProperty("material.smb.port")
        assumeNotNull("Run tools/network-tests.py to provision the SMB fixture", port)
        defaultWarningLogger = warningLogger
        // The server's watch ending is logged, and android.util.Log is a stub here.
        warningLogger = { _, _, _ -> }
        SmbFileSystemProvider.client = Client(
            object : Authenticator {
                override fun getPassword(authority: Authority) = "test-only"
            }
        )
        fileSystem = SmbFileSystemProvider.getOrNewFileSystem(
            Authority("127.0.0.1", port!!.toInt(), "test", null)
        )
        SmbFileSystemProvider.createDirectory(directory)
    }

    @After
    fun tearDown() {
        if (!::fileSystem.isInitialized) {
            return
        }
        threads.forEach { it.interrupt() }
        watchServices.forEach { it.close() }
        threads.forEach { it.join(TIMEOUT_MILLIS) }
        try {
            SmbFileSystemProvider.newDirectoryStream(directory, AcceptAll).use { stream ->
                stream.forEach { SmbFileSystemProvider.deleteIfExists(it) }
            }
            SmbFileSystemProvider.deleteIfExists(directory)
        } catch (e: Exception) {
            // The test that left something behind is the one that reports the failure.
        }
        fileSystem.close()
        warningLogger = defaultWarningLogger
    }

    private val directory: SmbPath
        get() = fileSystem.getPath("/test/notification-test")

    private fun path(name: String): SmbPath = fileSystem.getPath("/test/notification-test/$name")

    private fun write(name: String, content: String) {
        SmbFileSystemProvider.newOutputStream(
            path(name),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        ).use { it.write(content.toByteArray()) }
    }

    /** What this process tells observers of [directory], as (kind, file name) pairs. */
    private fun inProcessEvents(block: () -> Unit): Set<Pair<WatchEvent.Kind<*>, String>> {
        val watchService = LocalWatchService().also { watchServices += it }
        PathWatchSource.inProcess(directory).register(watchService)
        block()
        val events = mutableSetOf<Pair<WatchEvent.Kind<*>, String>>()
        while (true) {
            val key = watchService.poll() ?: break
            key.pollEvents().forEach {
                events += it.kind() to (it.context() as Path).fileName.toString()
            }
            key.reset()
        }
        return events
    }

    private fun startPoller(source: PathWatchSource, changes: LinkedBlockingQueue<Unit>) {
        val watchService = source.newWatchService().also { watchServices += it }
        val register = { source.register(watchService) }
        register()
        // No waiting before registering again, so that recovering takes no real time.
        val poller = WatchServicePoller(watchService, register, { changes.put(Unit) }, sleep = {})
        threads += Thread(poller).apply {
            isDaemon = true
            start()
        }
    }

    @Test
    fun creatingAFolderTellsTheFolderItIsIn() {
        assertEquals(
            setOf(StandardWatchEventKinds.ENTRY_CREATE to "new"),
            inProcessEvents { SmbFileSystemProvider.createDirectory(path("new")) }
        )
    }

    @Test
    fun writingAFileTellsTheFolderItIsIn() {
        val events = inProcessEvents { write("file.txt", "content") }
        assertTrue(events.toString(), StandardWatchEventKinds.ENTRY_CREATE to "file.txt" in events)
        assertTrue(events.toString(), StandardWatchEventKinds.ENTRY_MODIFY to "file.txt" in events)
    }

    @Test
    fun readingAFileTellsNobody() {
        write("file.txt", "content")
        assertEquals(
            emptySet<Any>(),
            inProcessEvents {
                SmbFileSystemProvider.newInputStream(path("file.txt")).use { it.readBytes() }
            }
        )
    }

    @Test
    fun renamingCopyingAndDeletingTellTheFolderTheyAreIn() {
        write("file.txt", "content")
        val moved =
            inProcessEvents { SmbFileSystemProvider.move(path("file.txt"), path("moved.txt")) }
        assertTrue(moved.toString(), StandardWatchEventKinds.ENTRY_DELETE to "file.txt" in moved)
        assertTrue(moved.toString(), StandardWatchEventKinds.ENTRY_CREATE to "moved.txt" in moved)
        val copied = inProcessEvents {
            SmbFileSystemProvider.copy(
                path("moved.txt"),
                path("copy.txt"),
                StandardCopyOption.COPY_ATTRIBUTES
            )
        }
        assertTrue(copied.toString(), StandardWatchEventKinds.ENTRY_CREATE to "copy.txt" in copied)
        assertEquals(
            setOf(StandardWatchEventKinds.ENTRY_DELETE to "copy.txt"),
            inProcessEvents { SmbFileSystemProvider.delete(path("copy.txt")) }
        )
    }

    @Test
    fun changingTheTimesOfAFileTellsTheFolderItIsIn() {
        write("file.txt", "content")
        val time = FileTime.fromMillis(1_600_000_000_000)
        assertEquals(
            setOf(StandardWatchEventKinds.ENTRY_MODIFY to "file.txt"),
            inProcessEvents {
                SmbFileSystemProvider.getFileAttributeView(
                    path("file.txt"),
                    BasicFileAttributeView::class.java
                )!!.setTimes(time, null, null)
            }
        )
    }

    @Test
    fun aFolderCreatedInTheAppReachesTheObserverOfTheFolderItIsIn() {
        val changes = LinkedBlockingQueue<Unit>()
        startPoller(PathWatchSource.inProcess(directory), changes)

        SmbFileSystemProvider.createDirectory(path("new"))

        assertNotNull(changes.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
    }

    @Test
    fun theServerWatchComesBackAfterTheServerDroppedIt() {
        val container = System.getProperty("material.smb.container")
        assumeNotNull("Run tools/network-tests.py to provision the SMB fixture", container)
        val changes = LinkedBlockingQueue<Unit>()
        startPoller(PathWatchSource.of(directory), changes)
        // Something the server watch reports, before the server drops it.
        execInContainer(container!!, "touch", "/share/notification-test/before")
        assertNotNull(changes.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        while (changes.poll(QUIET_MILLIS, TimeUnit.MILLISECONDS) != null) {
            // The server can report one change as several.
        }

        execInContainer(container, "smbcontrol", "smbd", "close-share", "test")

        // Once for the lost watch and once for having it again.
        assertNotNull(changes.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        assertNotNull(changes.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        assertNull(changes.poll(QUIET_MILLIS, TimeUnit.MILLISECONDS))
        // And the new watch sees what another client does.
        execInContainer(container, "touch", "/share/notification-test/after")
        assertNotNull(changes.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
    }

    private fun execInContainer(container: String, vararg command: String) {
        val process = ProcessBuilder("docker", "exec", container, *command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
    }

    private object AcceptAll : DirectoryStream.Filter<Path> {
        override fun accept(entry: Path): Boolean = true
    }

    companion object {
        private const val TIMEOUT_MILLIS = 10_000L
        private const val QUIET_MILLIS = 500L
    }
}
