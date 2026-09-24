/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.LinkOption
import java8.nio.file.OpenOption
import java8.nio.file.StandardOpenOption
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opening a file goes through these booleans, and the defaults matter: an empty set of options
 * means reading, and what makes no sense together is refused instead of silently done.
 */
class OpenOptionsTest {
    @Test
    fun openingAFileWithoutSayingHowMeansReading() {
        val options = optionsOf()
        assertTrue(options.read)
        assertFalse(options.write)
    }

    @Test
    fun appendingImpliesWriting() {
        val options = optionsOf(StandardOpenOption.APPEND)
        assertTrue(options.write)
        assertTrue(options.append)
        assertFalse(options.read)
    }

    @Test
    fun whatOnlyMakesSenseForWritingIsDroppedWhenReading() {
        val options = optionsOf(
            StandardOpenOption.READ,
            StandardOpenOption.CREATE,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.TRUNCATE_EXISTING
        )
        assertFalse(options.create)
        assertFalse(options.createNew)
        assertFalse(options.truncateExisting)
    }

    @Test
    fun writingKeepsWhatWasAskedFor() {
        val options = optionsOf(
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.SPARSE,
            StandardOpenOption.SYNC,
            StandardOpenOption.DSYNC
        )
        assertTrue(options.create)
        assertTrue(options.createNew)
        assertTrue(options.truncateExisting)
        assertTrue(options.sparse)
        assertTrue(options.sync)
        assertTrue(options.dsync)
    }

    @Test
    fun aFileToDeleteOnCloseIsNotOpenedThroughALink() {
        val options = optionsOf(StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE)
        assertTrue(options.deleteOnClose)
        assertTrue(options.noFollowLinks)
    }

    @Test
    fun notFollowingLinksIsRemembered() {
        assertTrue(optionsOf(LinkOption.NOFOLLOW_LINKS).noFollowLinks)
    }

    @Test
    fun readingAndAppendingAtOnceIsRefused() {
        assertThrows(IllegalStateException::class.java) {
            optionsOf(StandardOpenOption.READ, StandardOpenOption.APPEND)
        }
    }

    @Test
    fun appendingToAFileThatIsTruncatedFirstIsRefused() {
        assertThrows(IllegalStateException::class.java) {
            optionsOf(StandardOpenOption.APPEND, StandardOpenOption.TRUNCATE_EXISTING)
        }
    }

    @Test
    fun anOptionNobodyKnowsIsRefused() {
        val option = object : OpenOption {}
        assertThrows(UnsupportedOperationException::class.java) { optionsOf(option) }
    }

    private fun optionsOf(vararg options: OpenOption): OpenOptions = options.toOpenOptions()
}
