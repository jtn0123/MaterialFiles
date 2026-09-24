/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.File
import java.io.InterruptedIOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The search behind the file list: it walks the first level before diving into subdirectories,
 * matches any part of a name regardless of case, and reports what it found as it goes.
 */
class WalkFileTreeSearchableTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var fileSystem: LocalTestFileSystem

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("root")
        fileSystem = LocalTestFileSystem(root.toPath())
        File(root, "hello.txt").writeText("hello")
        File(root, "other.txt").writeText("other")
        File(root, "directory").mkdir()
        File(root, "directory/hello-too.txt").writeText("hello")
        File(root, "directory/nested").mkdir()
        File(root, "directory/nested/hello-deep.txt").writeText("hello")
    }

    private fun search(path: String, query: String, intervalMillis: Long = 0): List<List<String>> {
        val batches = mutableListOf<List<String>>()
        WalkFileTreeSearchable.search(
            fileSystem.getPath(path),
            query,
            intervalMillis
        ) { paths -> batches += paths.map { it.toString() } }
        return batches
    }

    @Test
    fun everyNameContainingTheQueryIsFoundAtAnyDepth() {
        val found = search("/", "hello").flatten()
        assertEquals(
            listOf("/directory/hello-too.txt", "/directory/nested/hello-deep.txt", "/hello.txt"),
            found.sorted()
        )
    }

    @Test
    fun theFirstLevelIsSearchedBeforeGoingDeeper() {
        val found = search("/", "hello").flatten()
        assertTrue(
            "first level first: $found",
            found.indexOf("/hello.txt") < found.indexOf("/directory/nested/hello-deep.txt")
        )
    }

    @Test
    fun theCaseOfTheQueryDoesNotMatter() {
        assertEquals(3, search("/", "HELLO").flatten().size)
    }

    @Test
    fun aDirectoryIsFoundByItsOwnNameButNeverTheOneBeingSearched() {
        val found = search("/directory", "ect").flatten()
        // "/directory" itself matches the query, yet it is where the search started.
        assertEquals(emptyList<String>(), found)
        assertEquals(listOf("/directory"), search("/", "irect").flatten())
    }

    @Test
    fun searchingAFileFindsNothing() {
        assertEquals(emptyList<List<String>>(), search("/hello.txt", "hello"))
    }

    @Test
    fun nothingMatchingMeansNothingIsReported() {
        assertEquals(emptyList<List<String>>(), search("/", "no-such-name"))
    }

    @Test
    fun whatIsFoundIsReportedAsTheWalkGoesOn() {
        val batches = search("/", "hello")
        assertTrue("reported while walking: $batches", batches.size > 1)
    }

    @Test
    fun anIntervalKeepsEverythingUntilTheEnd() {
        val batches = search("/", "hello", TimeUnit.MINUTES.toMillis(10))
        assertEquals(1, batches.size)
        assertEquals(3, batches[0].size)
    }

    @Test
    fun aBrokenSymbolicLinkIsStillFoundByItsName() {
        Files.createSymbolicLink(
            File(root, "hello-broken").toPath(),
            File(root, "missing").toPath()
        )
        Files.createSymbolicLink(
            File(root, "directory/hello-broken-deep").toPath(),
            File(root, "missing").toPath()
        )
        assertEquals(
            listOf("/directory/hello-broken-deep", "/hello-broken"),
            search("/", "broken").flatten().sorted()
        )
    }

    @Test
    fun aSearchStopsWhenItsThreadIsInterrupted() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) { search("/", "hello") }
        } finally {
            // The search cleared the flag by testing it; make sure the thread is clean either way.
            Thread.interrupted()
        }
    }
}
