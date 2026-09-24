/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java8.nio.file.FileVisitResult
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.SimpleFileVisitor
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.provider.common.LocalTestFileSystem
import me.zhanghai.android.files.provider.common.delete
import me.zhanghai.android.files.provider.common.readAttributes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [WalkErrorVisitor] over real files, made unreadable with file permissions: a directory without
 * read permission cannot be listed.
 */
class WalkErrorVisitorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var fileSystem: LocalTestFileSystem
    private val locked = mutableListOf<File>()

    private val visitedFiles = mutableListOf<String>()
    private val finishedDirectories = mutableListOf<String>()
    private val decided = mutableListOf<Pair<String, WalkFailure>>()

    private val recorder = object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            visitedFiles += file.toString()
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(directory: Path, exception: IOException?): FileVisitResult {
            if (exception != null) {
                throw exception
            }
            finishedDirectories += directory.toString()
            return FileVisitResult.CONTINUE
        }
    }

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("root")
        fileSystem = LocalTestFileSystem(root.toPath())
    }

    @After
    fun tearDown() {
        locked.forEach { unlock(it) }
    }

    @Test
    fun skippingAFolderThatCannotBeListedWalksTheRest() {
        createFiles("tree/a.txt", "tree/locked/x.txt", "tree/z.txt")
        lock("tree/locked")
        walk(path("/tree"), WalkErrorVisitor(recorder, decideAlways(ErrorDecision.SKIP)))
        assertEquals(listOf("/tree/locked" to WalkFailure.VISIT), decided)
        assertEquals(setOf("/tree/a.txt", "/tree/z.txt"), visitedFiles.toSet())
        // The folder above a skipped one was not walked completely.
        assertEquals(emptyList<String>(), finishedDirectories)
    }

    @Test
    fun retryingAfterTheFolderBecameReadableWalksIt() {
        createFiles("tree/a.txt", "tree/locked/x.txt")
        lock("tree/locked")
        val visitor = WalkErrorVisitor(recorder, { path, _, failure ->
            decided += path.toString() to failure
            unlock(file("tree/locked"))
            ErrorDecision.RETRY
        })
        walk(path("/tree"), visitor)
        assertEquals(listOf("/tree/locked" to WalkFailure.VISIT), decided)
        assertEquals(setOf("/tree/a.txt", "/tree/locked/x.txt"), visitedFiles.toSet())
        assertEquals(listOf("/tree/locked", "/tree"), finishedDirectories)
    }

    @Test
    fun skipAllSkipsLaterFailuresWithoutAsking() {
        createFiles("tree/one/x.txt", "tree/two/y.txt", "tree/z.txt")
        lock("tree/one")
        lock("tree/two")
        val job = TestFileJob()
        val all = ActionAllInfo()
        val transferInfo = transferInfoOf(5)
        var dialogs = 0
        val visitor = WalkErrorVisitor(recorder, { path, exception, failure ->
            job.decideOnWalkError(path, exception, failure, all, transferInfo) {
                ++dialogs
                ErrorResult(FileJobErrorAction.NEGATIVE, true)
            }
        })
        walk(path("/tree"), visitor)
        assertEquals(1, dialogs)
        assertTrue(all.skipWalkError)
        assertEquals(setOf(path("/tree/one"), path("/tree/two")), all.skippedWalkPaths)
        assertEquals(2, job.skippedErrorCount)
        assertEquals(3, transferInfo.fileCount)
        assertEquals(listOf("/tree/z.txt"), visitedFiles)
    }

    @Test
    fun aPathSkippedDuringTheScanIsSkippedAgainWithoutAskingOrCounting() {
        val job = TestFileJob()
        val all = ActionAllInfo()
        all.skippedWalkPaths.add(path("/locked"))
        val transferInfo = transferInfoOf(2)
        val decision = job.decideOnWalkError(
            path("/locked"),
            IOException(),
            WalkFailure.VISIT,
            all,
            transferInfo
        ) {
            fail("asked the user")
            throw AssertionError()
        }
        assertEquals(ErrorDecision.SKIP, decision)
        assertEquals(0, job.skippedErrorCount)
        assertEquals(2, transferInfo.fileCount)
    }

    @Test
    fun cancellingStopsTheWalk() {
        createFiles("tree/locked/x.txt")
        lock("tree/locked")
        try {
            walk(path("/tree"), WalkErrorVisitor(recorder, decideAlways(ErrorDecision.CANCEL)))
            fail("the walk went on")
        } catch (expected: InterruptedIOException) {
            // Cancelled, as the job expects.
        }
        assertEquals(1, decided.size)
        assertEquals(emptyList<String>(), finishedDirectories)
    }

    @Test
    fun aCancelledJobIsNotAskedAbout() {
        val visitor = WalkErrorVisitor(recorder, decideAlways(ErrorDecision.SKIP))
        val cancellation = InterruptedIOException()
        try {
            visitor.visitFileFailed(path("/file"), cancellation)
            fail("cancellation was swallowed")
        } catch (e: InterruptedIOException) {
            assertSame(cancellation, e)
        }
        assertEquals(emptyList<Pair<String, WalkFailure>>(), decided)
    }

    @Test
    fun retryingAListingThatBrokeOffWalksOnlyTheChildrenNotVisitedYet() {
        createFiles("tree/a.txt", "tree/b.txt")
        val visitor = WalkErrorVisitor(recorder, decideAlways(ErrorDecision.RETRY))
        val tree = path("/tree")
        visitor.preVisitDirectory(tree, tree.readAttributes(BasicFileAttributes::class.java))
        val a = path("/tree/a.txt")
        visitor.visitFile(a, a.readAttributes(BasicFileAttributes::class.java))
        visitor.postVisitDirectory(tree, IOException("broke off"))
        assertEquals(listOf("/tree" to WalkFailure.LIST), decided)
        assertEquals(listOf("/tree/a.txt", "/tree/b.txt"), visitedFiles)
        assertEquals(listOf("/tree"), finishedDirectories)
    }

    @Test
    fun deletingSkipsAFolderThatCannotBeListedAndEverythingAboveIt() {
        createFiles("tree/keep/locked/x.txt", "tree/keep/b.txt", "tree/c.txt", "tree/gone/d.txt")
        lock("tree/keep/locked")
        val visitor =
            WalkErrorVisitor(DeletingVisitor { it.delete() }, decideAlways(ErrorDecision.SKIP))
        walk(path("/tree"), visitor)
        assertEquals(listOf("/tree/keep/locked" to WalkFailure.VISIT), decided)
        unlock(file("tree/keep/locked"))
        assertTrue(file("tree/keep/locked/x.txt").exists())
        assertFalse(file("tree/keep/b.txt").exists())
        assertFalse(file("tree/c.txt").exists())
        assertFalse(file("tree/gone").exists())
    }

    @Test
    fun deletingSkipsAFolderWhoseListingBrokeOff() {
        createFiles("tree/a.txt", "tree/b.txt")
        val visitor =
            WalkErrorVisitor(DeletingVisitor { it.delete() }, decideAlways(ErrorDecision.SKIP))
        val tree = path("/tree")
        visitor.preVisitDirectory(tree, tree.readAttributes(BasicFileAttributes::class.java))
        val a = path("/tree/a.txt")
        visitor.visitFile(a, a.readAttributes(BasicFileAttributes::class.java))
        visitor.postVisitDirectory(tree, IOException("broke off"))
        assertFalse(file("tree/a.txt").exists())
        assertTrue(file("tree/b.txt").exists())
    }

    private fun decideAlways(decision: ErrorDecision): WalkErrorDecider = { path, _, failure ->
        decided += path.toString() to failure
        decision
    }

    private fun walk(start: Path, visitor: WalkErrorVisitor) {
        Files.walkFileTree(start, visitor)
    }

    private fun path(name: String): Path = fileSystem.getPath(name)

    private fun file(name: String): File = File(root, name)

    private fun createFiles(vararg names: String) {
        for (name in names) {
            val file = file(name)
            file.parentFile!!.mkdirs()
            file.writeText(name)
        }
    }

    private fun lock(name: String) {
        val file = file(name)
        assertTrue(file.setReadable(false, false))
        locked += file
        // Permissions mean nothing to root, and then nothing here would fail.
        assertFalse("$file is still readable; are the tests running as root?", file.canRead())
    }

    private fun unlock(file: File) {
        file.setReadable(true, false)
    }

    private fun transferInfoOf(fileCount: Int): TransferInfo {
        val scanInfo = ScanInfo()
        repeat(fileCount) { scanInfo.incrementFileCount() }
        return TransferInfo(scanInfo, null)
    }

    private class TestFileJob : FileJob() {
        override fun run() {
            // The job is only a receiver for the helpers under test.
        }
    }
}
