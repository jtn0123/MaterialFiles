/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.nio.file.Files
import java8.nio.file.FileSystemAlreadyExistsException
import java8.nio.file.FileSystemNotFoundException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** [FileSystemCache], which keeps one file system per key for as long as someone uses it. */
class FileSystemCacheTest {
    private val cache = FileSystemCache<String, LocalTestFileSystem>()

    private val directory = Files.createTempDirectory("file-system-cache")

    private var created = 0

    private fun newFileSystem(): LocalTestFileSystem {
        ++created
        return LocalTestFileSystem(directory)
    }

    @After
    fun tearDown() {
        Files.delete(directory)
    }

    @Test
    fun aFileSystemIsCreatedOnlyOncePerKey() {
        val fileSystem = cache.create("a", ::newFileSystem)
        assertSame(fileSystem, cache["a"])
        assertSame(fileSystem, cache.getOrCreate("a", ::newFileSystem))
        assertThrows(FileSystemAlreadyExistsException::class.java) {
            cache.create("a", ::newFileSystem)
        }
        assertEquals(1, created)
        assertNotSame(fileSystem, cache.getOrCreate("b", ::newFileSystem))
        assertEquals(2, created)
    }

    @Test
    fun anUnknownKeyHasNoFileSystem() {
        val exception = assertThrows(FileSystemNotFoundException::class.java) { cache["a"] }
        assertEquals("a", exception.message)
    }

    @Test
    fun aRemovedFileSystemIsForgottenButOnlyIfItIsTheCurrentOne() {
        val first = cache.create("a", ::newFileSystem)
        // Removing a file system that is not the one cached leaves the cached one alone.
        cache.remove("a", LocalTestFileSystem(directory))
        assertSame(first, cache["a"])
        cache.remove("a", first)
        assertThrows(FileSystemNotFoundException::class.java) { cache["a"] }
        // Removing again, or removing what was never there, is harmless.
        cache.remove("a", first)
        cache.remove("b", first)
        val second = cache.create("a", ::newFileSystem)
        assertNotSame(first, second)
    }
}
