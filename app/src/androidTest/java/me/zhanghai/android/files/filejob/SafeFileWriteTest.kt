package me.zhanghai.android.files.filejob

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java8.nio.file.Paths
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.linux.syscall.Syscall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SafeFileWriteTest {
    private var oldRootStrategy: me.zhanghai.android.files.provider.root.RootStrategy? = null

    @org.junit.Before fun useLocalProviderForAppPrivateFiles() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            oldRootStrategy = me.zhanghai.android.files.settings.Settings.ROOT_STRATEGY.value
            me.zhanghai.android.files.settings.Settings.ROOT_STRATEGY.putValue(
                me.zhanghai.android.files.provider.root.RootStrategy.NEVER
            )
        }
    }

    @org.junit.After fun restoreRootStrategy() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            oldRootStrategy?.let {
                me.zhanghai.android.files.settings.Settings.ROOT_STRATEGY.putValue(it)
            }
        }
    }

    @Test fun interruptedWritePreservesRealLocalFileAndSuccessPreservesMode() {
        val directory =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "safe-write-test"
            )
        directory.mkdirs()
        try {
            val original = File(directory, "original")
            original.writeText("original")
            Os.chmod(original.path, 384)
            Syscall.lsetxattr(
                original.path.toByteString(),
                "user.materialfiles".toByteString(),
                "attribute".toByteArray(),
                0
            )
            val path = Paths.get(original.path)
            assertThrows(IOException::class.java) {
                path.writeSafely {
                    it.write("partial".toByteArray())
                    throw IOException("disk full")
                }
            }
            assertEquals("original", original.readText())
            path.writeSafely { it.write("replacement".toByteArray()) }
            assertEquals("replacement", original.readText())
            assertEquals(
                "attribute",
                String(
                    Syscall.lgetxattr(
                        original.path.toByteString(),
                        "user.materialfiles".toByteString()
                    )
                )
            )
            assertEquals(384, Os.stat(original.path).st_mode and 511)
            assertEquals(listOf("original"), directory.list()!!.toList())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun savingThroughSymlinkKeepsTheLink() {
        val directory =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "safe-link-test"
            )
        directory.mkdirs()
        try {
            File(directory, "target").writeText("original")
            val link = File(directory, "link")
            Os.symlink("target", link.path)
            Paths.get(link.path).writeSafely { it.write("new".toByteArray()) }
            assertEquals("target", Os.readlink(link.path))
            assertEquals("new", File(directory, "target").readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun changedTargetIsNotOverwrittenByStagedSave() {
        val file = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "concurrent-save.txt"
        ).apply { writeText("original") }
        try {
            assertThrows(IOException::class.java) {
                Paths.get(file.path).writeSafely { stream ->
                    stream.write("staged edit".toByteArray())
                    file.writeText("newer external content")
                }
            }
            assertEquals("newer external content", file.readText())
        } finally {
            file.delete()
        }
    }
}
