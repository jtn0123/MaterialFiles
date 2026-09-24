/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.util.concurrent.TimeUnit
import java8.nio.file.LinkOption
import java8.nio.file.WatchEvent
import java8.nio.file.WatchKey
import java8.nio.file.WatchService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [ByteStringPath] is the path a provider returns when all it has is bytes, for instance the
 * target of a symbolic link that was never resolved. It is a byte string and nothing else, so
 * every path operation on it has to say so instead of inventing an answer.
 */
class ByteStringPathTest {
    private val path = ByteStringPath("/pictures/holiday.jpg".toByteString())

    @Test
    fun itKeepsTheBytesItWasGiven() {
        assertEquals("/pictures/holiday.jpg".toByteString(), path.toByteString())
        assertEquals("/pictures/holiday.jpg", path.toString())
    }

    @Test
    fun theBytesAreNotDecodedOrValidated() {
        val invalidUtf8 = ByteStringPath(byteArrayOf(0x2F, 0x61, 0xFF.toByte()).toByteString())
        assertEquals(3, invalidUtf8.toByteString().length)
        assertEquals(0xFF.toByte(), invalidUtf8.toByteString()[2])
    }

    @Test
    fun everyPathOperationIsUnsupportedBecauseThereIsNoFileSystemBehindIt() {
        val other = ByteStringPath("/other".toByteString())
        val operations = mapOf<String, () -> Any?>(
            "fileSystem" to { path.fileSystem },
            "isAbsolute" to { path.isAbsolute },
            "root" to { path.root },
            "fileName" to { path.fileName },
            "parent" to { path.parent },
            "nameCount" to { path.nameCount },
            "getName" to { path.getName(0) },
            "subpath" to { path.subpath(0, 1) },
            "startsWith(Path)" to { path.startsWith(other) },
            "startsWith(String)" to { path.startsWith("/pictures") },
            "endsWith(Path)" to { path.endsWith(other) },
            "endsWith(String)" to { path.endsWith("holiday.jpg") },
            "normalize" to { path.normalize() },
            "resolve(Path)" to { path.resolve(other) },
            "resolve(String)" to { path.resolve("other") },
            "resolveSibling(Path)" to { path.resolveSibling(other) },
            "resolveSibling(String)" to { path.resolveSibling("other") },
            "relativize" to { path.relativize(other) },
            "toUri" to { path.toUri() },
            "toAbsolutePath" to { path.toAbsolutePath() },
            "toRealPath" to { path.toRealPath(LinkOption.NOFOLLOW_LINKS) },
            "toFile" to { path.toFile() },
            "register" to { path.register(UnusedWatchService, arrayOf<WatchEvent.Kind<*>>()) },
            "register(vararg)" to { path.register(UnusedWatchService) },
            "iterator" to { path.iterator() },
            "compareTo" to { path.compareTo(other) }
        )
        for ((name, operation) in operations) {
            assertThrows(name, UnsupportedOperationException::class.java) { operation() }
        }
    }

    /** Never used: the path refuses before it looks at the watch service. */
    private object UnusedWatchService : WatchService {
        override fun close() = throw AssertionError()

        override fun poll(): WatchKey = throw AssertionError()

        override fun poll(timeout: Long, unit: TimeUnit): WatchKey = throw AssertionError()

        override fun take(): WatchKey = throw AssertionError()
    }
}
