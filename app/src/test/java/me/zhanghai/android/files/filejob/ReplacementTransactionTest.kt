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
        assertSerialized("target", "target")
    }

    @Test fun equivalentPathsShareTheSameReplacementLock() {
        assertSerialized(
            me.zhanghai.android.files.provider.common.TestPath("/target"),
            me.zhanghai.android.files.provider.common.TestPath("/folder/../target")
        )
    }

    @Test fun atomicReplacementForcesDataBeforeMoveAndDirectoryAfterward() {
        assertDurableReplacement(atomic = true)
    }

    @Test fun fallbackReplacementForcesDirectoryBeforeDeletingBackup() {
        assertDurableReplacement(atomic = false)
    }

    @Test fun failedStagedForcePreservesOriginal() {
        assertDurableReplacement(atomic = true, failAt = "stage")
    }

    @Test fun failedDirectoryForceKeepsFallbackRecoveryCopy() {
        assertDurableReplacement(atomic = false, failAt = "parent")
    }

    @Test fun failedDirectoryForceDoesNotReportAtomicSaveSuccess() {
        assertDurableReplacement(atomic = true, failAt = "parent")
    }

    private fun assertDurableReplacement(atomic: Boolean, failAt: String? = null) {
        val target = folder.root.toPath().resolve("durable")
        val stage = target.resolveSibling("stage")
        val backup = target.resolveSibling("backup")
        Files.writeString(target, "original")
        val events = mutableListOf<String>()
        val operation = {
            replaceTransaction(
                target,
                stage,
                backup,
                {
                    Files.writeString(it, "new")
                    events += "write"
                },
                { from, to ->
                    events += if (from == target) "backup" else "replace"
                    Files.move(from, to)
                    Unit
                },
                {
                    if (it == backup) events += "cleanup"
                    Files.deleteIfExists(it)
                    Unit
                },
                ReplacementCommit(
                    atomicReplace = if (atomic) {
                        { from, to ->
                            events += "replace"
                            Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            Unit
                        }
                    } else {
                        null
                    },
                    forceStaged = {
                        assertEquals("new", Files.readString(it))
                        assertEquals("original", Files.readString(target))
                        events += "force stage"
                        if (failAt == "stage") throw IOException("force failed")
                    },
                    forceParent = {
                        assertEquals(target, it)
                        assertEquals("new", Files.readString(it))
                        events += "force parent"
                        if (failAt == "parent") throw IOException("directory force failed")
                    }
                )
            )
        }
        if (failAt == null) {
            operation()
            val expected = if (atomic) {
                listOf("write", "force stage", "replace", "force parent")
            } else {
                listOf("write", "force stage", "backup", "replace", "force parent", "cleanup")
            }
            assertEquals(expected, events)
        } else {
            val error = assertThrows(IOException::class.java, operation)
            if (failAt == "stage") {
                assertEquals("original", Files.readString(target))
                assertEquals(listOf("write", "force stage"), events)
            } else if (!atomic) {
                assertEquals("original", Files.readString(backup))
                org.junit.Assert.assertTrue(error.message!!.contains(backup.toString()))
            }
        }
        assertFalse(Files.exists(stage))
    }

    private fun <T> assertSerialized(firstTarget: T, secondTarget: T) {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val enteredFirst = java.util.concurrent.CountDownLatch(1)
        val releaseFirst = java.util.concurrent.CountDownLatch(1)
        val enteredSecond = java.util.concurrent.CountDownLatch(1)
        val startedSecond = java.util.concurrent.CountDownLatch(1)
        val seconds = java.util.concurrent.TimeUnit.SECONDS
        try {
            val first = executor.submit {
                replaceTransaction(firstTarget, firstTarget, firstTarget, {
                    enteredFirst.countDown()
                    check(releaseFirst.await(5, seconds))
                }, { _, _ -> }, {})
            }
            check(enteredFirst.await(5, seconds))
            val second = executor.submit {
                startedSecond.countDown()
                replaceTransaction(secondTarget, secondTarget, secondTarget, {
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
