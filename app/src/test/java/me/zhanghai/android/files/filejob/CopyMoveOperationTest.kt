/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import java.io.IOException
import java.io.InterruptedIOException
import java.text.Collator
import java8.nio.file.CopyOption
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.StandardCopyOption
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import kotlin.reflect.KMutableProperty0
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.provider.common.ProgressCopyOption
import me.zhanghai.android.files.provider.common.TestPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CopyMoveOperationTest {
    private val source = TestPath("/share/a")
    private val target = TestPath("/backup/a")

    private val host = FakeHost()
    private val transferInfo = transferInfoOf(1)
    private val actionAllInfo = ActionAllInfo()

    private fun run(
        target: Path = this.target,
        source: Path = this.source,
        type: CopyMoveType = CopyMoveType.COPY,
        copyAttributes: Boolean = false
    ): Boolean = CopyMoveOperation(host, source, type, copyAttributes, transferInfo, actionAllInfo)
        .run(target)

    @Test
    fun aTransferThatWorksIsCountedAndDescendedInto() {
        assertTrue(run())
        assertEquals(listOf<Path>(target), host.targets)
        assertEquals(1, transferInfo.transferredFileCount)
        assertEquals(0, host.skippedCount)
        assertTrue(host.notificationCount >= 2)
    }

    @Test
    fun theOptionsFollowNoLinksAndCopyAttributesOnlyWhenAsked() {
        run()
        assertEquals(listOf(LinkOption.NOFOLLOW_LINKS), host.options.single().plainOptions())
        run(copyAttributes = true)
        assertEquals(
            listOf(LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES),
            host.options.last().plainOptions()
        )
    }

    @Test
    fun progressIsAddedToTheTransferredSize() {
        host.onTransfer = { _, options ->
            options.filterIsInstance<ProgressCopyOption>().single().listener(42)
        }
        run()
        assertEquals(42, transferInfo.transferredSize)
    }

    @Test
    fun copyingIntoItselfIsRefusedAndSkipped() {
        host.refusalResult = ErrorResult(FileJobErrorAction.POSITIVE, false)
        assertFalse(run(TestPath("/share/a/b/a")))
        assertEquals(
            listOf(
                R.string.file_job_cannot_copy_into_itself_title to
                    R.string.file_job_cannot_copy_move_into_itself_message
            ),
            host.refusals
        )
        assertEquals(emptyList<Path>(), host.targets)
        assertEquals(1, host.skippedCount)
    }

    @Test
    fun movingOverAnAncestorIsRefusedOnceForAll() {
        host.refusalResult = ErrorResult(FileJobErrorAction.POSITIVE, true)
        assertFalse(run(TestPath("/share"), type = CopyMoveType.MOVE))
        assertFalse(run(TestPath("/share"), type = CopyMoveType.MOVE))
        assertEquals(
            listOf(
                R.string.file_job_cannot_move_over_itself_title to
                    R.string.file_job_cannot_copy_move_over_itself_message
            ),
            host.refusals
        )
        assertTrue(actionAllInfo.skipCopyMoveOverItself)
        assertEquals(2, host.skippedCount)
    }

    @Test(expected = InterruptedIOException::class)
    fun cancellingARefusalCancelsTheJob() {
        host.refusalResult = ErrorResult(FileJobErrorAction.NEGATIVE, false)
        run(TestPath("/share/a/b"))
    }

    @Test
    fun replacingRetriesWithReplaceExisting() {
        host.failFirst(FileAlreadyExistsException(target.toString()))
        host.conflictResult = ConflictResult(FileJobConflictAction.MERGE_OR_REPLACE, null, false)
        assertTrue(run())
        assertEquals(listOf<Path>(target, target), host.targets)
        assertFalse(StandardCopyOption.REPLACE_EXISTING in host.options[0])
        assertTrue(StandardCopyOption.REPLACE_EXISTING in host.options[1])
        assertEquals(1, host.conflicts.size)
    }

    @Test
    fun renamingRetriesBesideTheTarget() {
        host.failFirst(FileAlreadyExistsException(target.toString()))
        host.conflictResult = ConflictResult(FileJobConflictAction.RENAME, "a (1)", false)
        assertTrue(run())
        assertEquals(listOf<Path>(target, TestPath("/backup/a (1)")), host.targets)
        assertFalse(StandardCopyOption.REPLACE_EXISTING in host.options[1])
    }

    @Test
    fun aRememberedMergeCountsTheDirectoryWithoutAsking() {
        actionAllInfo.merge = true
        host.directories.addAll(listOf(source, target))
        host.sizes[target] = 7
        host.failFirst(FileAlreadyExistsException(target.toString()))
        assertTrue(run())
        assertEquals(emptyList<Pair<FileItem, FileItem>>(), host.conflicts)
        assertEquals(1, host.targets.size)
        assertEquals(1, transferInfo.transferredFileCount)
        assertEquals(7, transferInfo.transferredSize)
    }

    @Test
    fun aFileIsNeverPutInPlaceOfADirectory() {
        val exception = FileAlreadyExistsException(target.toString())
        host.directories.add(target)
        host.failFirst(exception)
        try {
            run()
            fail("the conflict was resolved")
        } catch (e: FileAlreadyExistsException) {
            assertSame(exception, e)
        }
    }

    @Test
    fun skippingAConflictForAllIsRemembered() {
        host.failFirst(FileAlreadyExistsException(target.toString()))
        host.conflictResult = ConflictResult(FileJobConflictAction.SKIP, null, true)
        assertFalse(run())
        assertTrue(actionAllInfo.skipReplace)
        assertEquals(1, host.skippedCount)
    }

    @Test(expected = InterruptedIOException::class)
    fun cancellingAConflictCancelsTheJob() {
        host.failFirst(FileAlreadyExistsException(target.toString()))
        host.conflictResult = ConflictResult(FileJobConflictAction.CANCEL, null, false)
        run()
    }

    @Test
    fun anErrorIsRetriedAsDecided() {
        val exception = IOException("busy")
        host.failFirst(exception)
        host.errorDecision = ErrorDecision.RETRY
        assertTrue(run())
        assertEquals(listOf(exception), host.errors)
        assertEquals(2, host.targets.size)
    }

    @Test
    fun aSkippedErrorLeavesTheSourceBehind() {
        host.failFirst(IOException())
        host.errorDecision = ErrorDecision.SKIP
        assertFalse(run())
        assertEquals(1, host.skippedCount)
        assertEquals(0, transferInfo.transferredFileCount)
    }

    @Test(expected = InterruptedIOException::class)
    fun cancellingAnErrorCancelsTheJob() {
        host.failFirst(IOException())
        host.errorDecision = ErrorDecision.CANCEL
        run()
    }

    @Test
    fun aCancelledTransferIsNotDecidedOn() {
        host.failFirst(InterruptedIOException())
        try {
            run()
            fail("cancellation was swallowed")
        } catch (e: InterruptedIOException) {
            assertEquals(emptyList<IOException>(), host.errors)
        }
    }

    private fun Array<CopyOption>.plainOptions(): List<CopyOption> =
        filterNot { it is ProgressCopyOption }

    private fun transferInfoOf(fileCount: Int): TransferInfo {
        val scanInfo = ScanInfo()
        repeat(fileCount) { scanInfo.incrementFileCount() }
        return TransferInfo(scanInfo, null)
    }

    private class FakeHost : CopyMoveHost {
        val targets = mutableListOf<Path>()
        val options = mutableListOf<Array<CopyOption>>()
        var onTransfer: (Path, Array<CopyOption>) -> Unit = { _, _ -> }
        private val failures = ArrayDeque<IOException>()

        val directories = mutableSetOf<Path>()
        val sizes = mutableMapOf<Path, Long>()

        var skippedCount = 0
        var notificationCount = 0

        var refusalResult: ErrorResult? = null
        val refusals = mutableListOf<Pair<Int, Int>>()

        var errorDecision: ErrorDecision? = null
        val errors = mutableListOf<IOException>()

        var conflictResult: ConflictResult? = null
        val conflicts = mutableListOf<Pair<FileItem, FileItem>>()

        fun failFirst(exception: IOException) {
            failures += exception
        }

        override fun transfer(target: Path, options: Array<CopyOption>) {
            targets.add(target)
            this.options += options
            failures.removeFirstOrNull()?.let { throw it }
            onTransfer(target, options)
        }

        override fun loadFileItem(path: Path): FileItem = FileItem(
            path,
            Collator.getInstance().getCollationKey(path.toString()),
            TestAttributes(path in directories, sizes[path] ?: 0),
            null,
            null,
            false,
            if (path in directories) MimeType.DIRECTORY else MimeType.GENERIC
        )

        override fun skipSource() {
            ++skippedCount
        }

        override fun postNotification() {
            ++notificationCount
        }

        override fun showRefusalDialog(titleRes: Int, messageRes: Int): ErrorResult {
            refusals += titleRes to messageRes
            return refusalResult ?: throw AssertionError("asked to refuse")
        }

        override fun decideOnError(
            target: Path,
            exception: IOException,
            skipAll: KMutableProperty0<Boolean>
        ): ErrorDecision {
            errors += exception
            return errorDecision ?: throw AssertionError("asked about $exception")
        }

        override fun showConflictDialog(
            sourceFile: FileItem,
            targetFile: FileItem
        ): ConflictResult {
            conflicts += sourceFile to targetFile
            return conflictResult ?: throw AssertionError("asked about a conflict")
        }
    }

    private class TestAttributes(private val isDirectory: Boolean, private val size: Long) :
        BasicFileAttributes {
        override fun lastModifiedTime(): FileTime = FileTime.fromMillis(0)

        override fun lastAccessTime(): FileTime = FileTime.fromMillis(0)

        override fun creationTime(): FileTime = FileTime.fromMillis(0)

        override fun isRegularFile(): Boolean = !isDirectory

        override fun isDirectory(): Boolean = isDirectory

        override fun isSymbolicLink(): Boolean = false

        override fun isOther(): Boolean = false

        override fun size(): Long = size

        override fun fileKey(): Any? = null
    }
}
