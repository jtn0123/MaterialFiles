package me.zhanghai.android.files.filejob

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReplacementTransactionTest {
    @get:Rule val folder = TemporaryFolder()

    private fun replace(
        failWrite: Boolean = false,
        failCommit: Boolean = false,
        failRollback: Boolean = false
    ): Path {
        val target = folder.root.toPath().resolve("original")
        Files.writeString(target, "original contents")
        val stage = target.resolveSibling("staged")
        val backup = target.resolveSibling("backup")
        val operation = {
            replaceTransaction(target, stage, backup, { path ->
                Files.writeString(path, "new contents")
                if (failWrite) throw IOException("disk full")
            }, { from, to ->
                if ((failCommit && from == stage) || (failRollback && from == backup)) {
                    throw IOException("rename failed")
                }
                Files.move(from, to)
                Unit
            }, {
                Files.deleteIfExists(it)
                Unit
            })
        }
        if (failWrite || failCommit) {
            assertThrows(IOException::class.java, operation)
            assertEquals(
                "original contents",
                Files.readString(if (failRollback) backup else target)
            )
        } else {
            operation()
            assertEquals("new contents", Files.readString(target))
            assertFalse(Files.exists(backup))
        }
        assertFalse(Files.exists(stage))
        return target
    }

    @Test fun failedWritePreservesOriginal() {
        replace(failWrite = true)
    }

    @Test fun failedCommitRestoresOriginal() {
        replace(failCommit = true)
    }

    @Test fun failedRollbackKeepsRecoveryCopy() {
        replace(failCommit = true, failRollback = true)
    }

    @Test fun successfulWriteReplacesOriginal() {
        replace()
    }

    @Test fun concurrentTransactionsForOneTargetAreSerialized() {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val enteredFirst = java.util.concurrent.CountDownLatch(1)
        val releaseFirst = java.util.concurrent.CountDownLatch(1)
        val enteredSecond = java.util.concurrent.CountDownLatch(1)
        val startedSecond = java.util.concurrent.CountDownLatch(1)
        val seconds = java.util.concurrent.TimeUnit.SECONDS
        try {
            val first = executor.submit {
                replaceTransaction("target", "stage1", "backup1", {
                    enteredFirst.countDown()
                    check(releaseFirst.await(5, seconds))
                }, { _, _ -> }, {})
            }
            check(enteredFirst.await(5, seconds))
            val second = executor.submit {
                startedSecond.countDown()
                replaceTransaction("target", "stage2", "backup2", {
                    enteredSecond.countDown()
                }, { _, _ -> }, {})
            }
            check(startedSecond.await(5, seconds))
            assertFalse(enteredSecond.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            first.get(5, seconds)
            second.get(5, seconds)
            assertEquals(0L, enteredSecond.count)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }
}
