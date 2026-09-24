/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files as JavaFiles
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.Continuation
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.provider.common.LocalTestFileSystem
import me.zhanghai.android.files.provider.common.UserAction
import me.zhanghai.android.files.provider.common.UserActionRequiredException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The job-level glue around [WalkErrorVisitor]: the scan, the walk of the jobs that set an
 * attribute and the decider a job asks with, over real files made unreadable with permissions.
 */
class FileJobWalkTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var fileSystem: LocalTestFileSystem
    private val locked = mutableListOf<File>()

    private val job = object : FileJob() {
        override fun run() {
            // The job is only a receiver for the helpers under test.
        }
    }

    private val decisions = ScriptedDecisions().also { job.decisions = it }

    private val scanProgress = mutableListOf<Pair<Int, Int>>()

    init {
        job.postScanProgress =
            { scanInfo, titleRes -> scanProgress += scanInfo.fileCount to titleRes }
    }

    private val decided = mutableListOf<Pair<String, WalkFailure>>()
    private val visited = mutableListOf<String>()

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("root")
        fileSystem = LocalTestFileSystem(root.toPath())
    }

    @After
    fun tearDown() {
        locked.forEach { it.setReadable(true, false) }
    }

    @Test
    fun theScanCountsEveryFileAndFolderWithTheirSize() {
        createFiles("tree/a.txt", "tree/sub/b.txt", "single.txt")
        val progress = mutableListOf<Int>()
        val scanInfo = job.countFiles(listOf(path("/tree"), path("/single.txt")), ActionAllInfo()) {
            progress += it.fileCount
        }
        val paths = listOf("tree", "tree/a.txt", "tree/sub", "tree/sub/b.txt", "single.txt")
        assertEquals(paths.size, scanInfo.fileCount)
        assertEquals(paths.sumOf { JavaFiles.size(file(it).toPath()) }, scanInfo.size)
        assertEquals((1..paths.size).toList(), progress)
    }

    @Test
    fun aJobScanPostsItsProgressAndThenItsTotal() {
        createFiles("tree/a.txt", "single.txt")
        val scanInfo = job.scan(listOf(path("/tree"), path("/single.txt")), SCAN_TITLE)
        assertEquals(3, scanInfo.fileCount)
        assertEquals(listOf(1, 2, 3, 3).map { it to SCAN_TITLE }, scanProgress)
    }

    @Test
    fun aRecursiveScanOfOnePathCountsEverythingUnderIt() {
        createFiles("tree/a.txt", "tree/sub/b.txt")
        val scanInfo = job.scan(path("/tree"), true, SCAN_TITLE, ActionAllInfo())
        assertEquals(4, scanInfo.fileCount)
        assertEquals(4 to SCAN_TITLE, scanProgress.last())
    }

    @Test
    fun aNonRecursiveScanCountsOnlyThePath() {
        createFiles("tree/a.txt", "tree/sub/b.txt")
        val scanInfo = job.scan(path("/tree"), false, SCAN_TITLE, ActionAllInfo())
        assertEquals(1, scanInfo.fileCount)
        assertEquals(JavaFiles.size(file("tree").toPath()), scanInfo.size)
        assertEquals(listOf(1 to SCAN_TITLE), scanProgress)
    }

    @Test
    fun aJobSettingAnAttributeScansFirstAndCountsWhatItSets() {
        createFiles("tree/a.txt", "tree/sub/b.txt")
        val transferInfos = mutableSetOf<TransferInfo>()
        job.walkSettingAttribute(path("/tree"), true, SCAN_TITLE) { file, _, transferInfo, _ ->
            visited += file.toString()
            transferInfo.incrementTransferredFileCount()
            transferInfos += transferInfo
        }
        assertEquals(
            listOf("/tree", "/tree/a.txt", "/tree/sub", "/tree/sub/b.txt"),
            visited.sorted()
        )
        assertEquals("/tree", visited.first())
        val transferInfo = transferInfos.single()
        // The scan counted what the walk then set.
        assertEquals(4, transferInfo.fileCount)
        assertEquals(4, transferInfo.transferredFileCount)
        assertEquals(4 to SCAN_TITLE, scanProgress.last())
    }

    @Test
    fun theScanLeavesOutAndRemembersAFolderSkippedForAll() {
        createFiles("tree/a.txt", "tree/locked/x.txt", "tree/locked/y.txt")
        lock("tree/locked")
        val all = ActionAllInfo(skipWalkError = true)
        val scanInfo = job.countFiles(listOf(path("/tree")), all) {}
        // The tree and a.txt; nothing of the folder that could not be listed.
        assertEquals(2, scanInfo.fileCount)
        assertEquals(setOf(path("/tree/locked")), all.skippedWalkPaths)
        assertEquals(1, job.skippedErrorCount)
    }

    @Test
    fun aJobSkipsForAllWithoutAskingAndCountsTheSkippedFile() {
        val all = ActionAllInfo(skipWalkError = true)
        val transferInfo = transferInfoOf(3)
        val decide = job.walkErrorDecider(all, transferInfo)
        assertEquals(ErrorDecision.SKIP, decide(path("/a"), IOException(), WalkFailure.VISIT))
        assertEquals(ErrorDecision.SKIP, decide(path("/b"), IOException(), WalkFailure.LIST))
        assertEquals(setOf(path("/a"), path("/b")), all.skippedWalkPaths)
        assertEquals(2, job.skippedErrorCount)
        // Only the file that could not be visited is left out; a listing is not a file.
        assertEquals(2, transferInfo.fileCount)
    }

    @Test
    fun aJobRetriesOnceTheUserHasDoneWhatTheWalkNeeded() {
        decisions.userActionResult = true
        val exception = TestUserActionRequiredException()
        val all = ActionAllInfo()
        val decide = job.walkErrorDecider(all, null)
        assertEquals(ErrorDecision.RETRY, decide(path("/a"), exception, WalkFailure.VISIT))
        assertEquals(listOf<UserActionRequiredException>(exception), decisions.userActions)
        assertTrue(all.skippedWalkPaths.isEmpty())
        assertEquals(0, job.skippedErrorCount)
    }

    @Test
    fun aWalkErrorIsDescribedByWhatFailed() {
        assertEquals(
            R.string.file_job_read_error_message_format,
            walkErrorMessageRes(WalkFailure.VISIT)
        )
        assertEquals(
            R.string.file_job_list_error_message_format,
            walkErrorMessageRes(WalkFailure.LIST)
        )
        assertNotEquals(
            walkErrorMessageRes(WalkFailure.VISIT),
            walkErrorMessageRes(WalkFailure.LIST)
        )
    }

    @Test
    fun settingAnAttributeRecursivelyVisitsAFolderBeforeItsChildren() {
        createFiles("tree/sub/a.txt")
        job.walkSettingAttribute(path("/tree"), true, decideAlways(ErrorDecision.SKIP), visit)
        assertEquals(listOf("/tree", "/tree/sub", "/tree/sub/a.txt"), visited)
        assertEquals(emptyList<Pair<String, WalkFailure>>(), decided)
    }

    @Test
    fun settingAnAttributeNonRecursivelyVisitsOnlyTheFolder() {
        createFiles("tree/a.txt")
        job.walkSettingAttribute(path("/tree"), false, decideAlways(ErrorDecision.SKIP), visit)
        assertEquals(listOf("/tree"), visited)
    }

    @Test
    fun retryingTheStartWalksItAsTheJobAskedNotRecursively() {
        val decide: WalkErrorDecider = { path, _, failure ->
            decided += path.toString() to failure
            // The folder shows up, with a child the non-recursive job must leave alone.
            createFiles("late/child.txt")
            ErrorDecision.RETRY
        }
        job.walkSettingAttribute(path("/late"), false, decide, visit)
        assertEquals(listOf("/late" to WalkFailure.VISIT), decided)
        assertEquals(listOf("/late"), visited)
    }

    @Test
    fun retryingTheStartFolderOnceItCanBeListedWalksItsChildren() {
        createFiles("tree/a.txt")
        lock("tree")
        val decide: WalkErrorDecider = { path, _, failure ->
            decided += path.toString() to failure
            file("tree").setReadable(true, false)
            ErrorDecision.RETRY
        }
        job.walkSettingAttribute(path("/tree"), true, decide, visit)
        assertEquals(listOf("/tree" to WalkFailure.VISIT), decided)
        assertEquals(listOf("/tree", "/tree/a.txt"), visited)
    }

    @Test
    fun aFolderBelowTheStartIsRetriedWithAPlainWalk() {
        createFiles("tree/locked/sub/x.txt")
        lock("tree/locked")
        val decide: WalkErrorDecider = { path, _, failure ->
            decided += path.toString() to failure
            file("tree/locked").setReadable(true, false)
            ErrorDecision.RETRY
        }
        job.walkSettingAttribute(path("/tree"), true, decide, visit)
        assertEquals(listOf("/tree/locked" to WalkFailure.VISIT), decided)
        assertEquals(
            listOf("/tree", "/tree/locked", "/tree/locked/sub", "/tree/locked/sub/x.txt"),
            visited
        )
    }

    @Test
    fun aSkippedFolderIsLeftOutOfSettingTheAttribute() {
        createFiles("tree/locked/x.txt", "tree/a.txt")
        lock("tree/locked")
        job.walkSettingAttribute(path("/tree"), true, decideAlways(ErrorDecision.SKIP), visit)
        assertEquals(listOf("/tree/locked" to WalkFailure.VISIT), decided)
        assertEquals(listOf("/tree", "/tree/a.txt"), visited)
    }

    private val visit: (Path, BasicFileAttributes) -> Unit = { path, _ ->
        visited += path.toString()
    }

    private fun decideAlways(decision: ErrorDecision): WalkErrorDecider = { path, _, failure ->
        decided += path.toString() to failure
        decision
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

    private fun transferInfoOf(fileCount: Int): TransferInfo {
        val scanInfo = ScanInfo()
        repeat(fileCount) { scanInfo.incrementFileCount() }
        return TransferInfo(scanInfo, null)
    }

    private companion object {
        val SCAN_TITLE = R.plurals.file_job_copy_scan_notification_title_format
    }

    private class ScriptedDecisions : FileJobDecisions {
        var userActionResult = false
        val userActions = mutableListOf<UserActionRequiredException>()

        override fun error(request: FileJobErrorRequest): ErrorResult =
            throw AssertionError("No dialog is expected here")

        override fun conflict(source: FileItem, target: FileItem, type: CopyMoveType) =
            throw AssertionError("No conflicts here")

        override fun userAction(exception: UserActionRequiredException): Boolean {
            userActions += exception
            return userActionResult
        }
    }

    private class TestUserActionRequiredException : UserActionRequiredException("/a") {
        override fun getUserAction(
            continuation: Continuation<Boolean>,
            context: Context
        ): UserAction = throw AssertionError("The decisions are scripted")
    }
}
