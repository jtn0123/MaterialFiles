/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java.io.IOException
import java8.nio.file.AtomicMoveNotSupportedException
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The algorithm in [AbstractCopyMove] against an in-memory provider that can be told to fail at
 * a given step, which is how the guarantees in its documentation are pinned down.
 */
class AbstractCopyMoveTest {
    private val fs = MemoryCopyMove()

    @Test
    fun copyWritesANewFileAndCopiesAttributes() {
        fs.files["/a"] = MemoryFile.regular("hello", mtime = 7)
        fs.copy("/a", "/b", options())
        assertEquals("hello", fs.files["/b"]!!.content)
        assertEquals(7, fs.files["/b"]!!.mtime)
        assertEquals(listOf(5L), fs.progress)
    }

    @Test
    fun copyingOntoTheSameFileIsANoOpThatReportsProgress() {
        fs.files["/a"] = MemoryFile.regular("hello")
        fs.copy("/a", "/a", options())
        assertEquals(listOf("/a"), fs.files.keys.sorted())
        assertEquals(listOf(5L), fs.progress)
        assertTrue(fs.log.none { it.startsWith("write") })
    }

    @Test
    fun copyRefusesAnExistingTargetWithoutReplaceExisting() {
        fs.files["/a"] = MemoryFile.regular("new")
        fs.files["/b"] = MemoryFile.regular("old")
        assertThrows(FileAlreadyExistsException::class.java) { fs.copy("/a", "/b", options()) }
        assertEquals("old", fs.files["/b"]!!.content)
    }

    @Test
    fun replacingWritesBesideTheTargetAndRenamesOverIt() {
        fs.files["/a"] = MemoryFile.regular("new")
        fs.files["/b"] = MemoryFile.regular("old")
        fs.copy("/a", "/b", options(replaceExisting = true))
        assertEquals("new", fs.files["/b"]!!.content)
        assertEquals(listOf("/a", "/b"), fs.files.keys.sorted())
        val write = fs.log.indexOfFirst { it.startsWith("write /b.") }
        val rename = fs.log.indexOfFirst { it.startsWith("rename /b.") }
        assertTrue("wrote beside the target: ${fs.log}", write != -1)
        assertTrue("renamed after writing: ${fs.log}", rename > write)
    }

    @Test
    fun aFailedReplacementLeavesTheOriginalAndNoSibling() {
        fs.files["/a"] = MemoryFile.regular("new")
        fs.files["/b"] = MemoryFile.regular("old")
        fs.failOn = "write"
        assertThrows(IOException::class.java) {
            fs.copy("/a", "/b", options(replaceExisting = true))
        }
        assertEquals("old", fs.files["/b"]!!.content)
        assertEquals(listOf("/a", "/b"), fs.files.keys.sorted())
    }

    @Test
    fun aFailedFirstCopyRemovesThePartialTarget() {
        fs.files["/a"] = MemoryFile.regular("new")
        fs.failOn = "write"
        assertThrows(IOException::class.java) { fs.copy("/a", "/b", options()) }
        assertNull(fs.files["/b"])
    }

    @Test
    fun aFailedRenameOverTheTargetRemovesTheSibling() {
        fs.files["/a"] = MemoryFile.regular("new")
        fs.files["/b"] = MemoryFile.regular("old")
        fs.failOn = "rename"
        assertThrows(IOException::class.java) {
            fs.copy("/a", "/b", options(replaceExisting = true))
        }
        assertEquals("old", fs.files["/b"]!!.content)
        assertEquals(listOf("/a", "/b"), fs.files.keys.sorted())
    }

    @Test
    fun copyingADirectoryReplacesAnExistingFile() {
        fs.files["/d"] = MemoryFile.directory()
        fs.files["/e"] = MemoryFile.regular("old")
        fs.copy("/d", "/e", options(replaceExisting = true))
        assertTrue(fs.files["/e"]!!.isDirectory)
    }

    @Test
    fun copyingALinkOverAnExistingFileDeletesItFirstOnlyWhenReplacing() {
        fs.files["/l"] = MemoryFile.link("/target")
        fs.files["/m"] = MemoryFile.regular("old")
        assertThrows(FileAlreadyExistsException::class.java) { fs.copy("/l", "/m", options()) }
        fs.copy("/l", "/m", options(replaceExisting = true))
        assertEquals("/target", fs.files["/m"]!!.linkTarget)
    }

    @Test
    fun specialFilesCannotBeCopied() {
        fs.files["/s"] = MemoryFile(MemoryFile.Type.OTHER)
        assertThrows(FileSystemException::class.java) { fs.copy("/s", "/t", options()) }
    }

    @Test
    fun copyWithAtomicMoveIsUnsupported() {
        fs.files["/a"] = MemoryFile.regular("x")
        assertThrows(UnsupportedOperationException::class.java) {
            fs.copy("/a", "/b", options(atomicMove = true))
        }
    }

    @Test
    fun moveRenamesWhenItCan() {
        fs.files["/a"] = MemoryFile.regular("hello")
        fs.move("/a", "/b", options())
        assertEquals(listOf("/b"), fs.files.keys.sorted())
        assertEquals(listOf("rename /a /b false"), fs.log.filter { it.startsWith("rename") })
        assertEquals(listOf(5L), fs.progress)
    }

    @Test
    fun moveOverAnExistingTargetRenamesWithReplace() {
        fs.files["/a"] = MemoryFile.regular("new")
        fs.files["/b"] = MemoryFile.regular("old")
        fs.move("/a", "/b", options(replaceExisting = true))
        assertEquals("new", fs.files["/b"]!!.content)
        assertTrue(fs.log.contains("rename /a /b true"))
    }

    @Test
    fun moveFallsBackToCopyAndDeleteWhenRenameFails() {
        fs.files["/a"] = MemoryFile.regular("hello")
        fs.failOn = "rename"
        fs.move("/a", "/b", options())
        assertEquals(listOf("/b"), fs.files.keys.sorted())
        assertEquals("hello", fs.files["/b"]!!.content)
    }

    @Test
    fun moveThatCannotDeleteTheSourceRemovesTheCopy() {
        fs.files["/a"] = MemoryFile.regular("hello")
        fs.failOn = "rename"
        fs.failDeleteOf = "/a"
        assertThrows(IOException::class.java) { fs.move("/a", "/b", options()) }
        assertEquals(listOf("/a"), fs.files.keys.sorted())
    }

    @Test
    fun atomicMoveFailsInsteadOfFallingBack() {
        fs.files["/a"] = MemoryFile.regular("hello")
        fs.failOn = "rename"
        assertThrows(IOException::class.java) { fs.move("/a", "/b", options(atomicMove = true)) }
        assertEquals(listOf("/a"), fs.files.keys.sorted())
    }

    @Test
    fun atomicMoveAcrossProvidersIsNotSupported() {
        fs.files["/a"] = MemoryFile.regular("hello")
        fs.canRenameForTest = false
        assertThrows(AtomicMoveNotSupportedException::class.java) {
            fs.move("/a", "/b", options(atomicMove = true))
        }
        fs.move("/a", "/b", options())
        assertEquals(listOf("/b"), fs.files.keys.sorted())
        assertFalse(fs.log.any { it.startsWith("rename") })
    }

    @Test
    fun moveOntoItselfIsANoOp() {
        fs.files["/a"] = MemoryFile.regular("hello")
        fs.move("/a", "/a", options())
        assertEquals("hello", fs.files["/a"]!!.content)
        assertEquals(listOf(5L), fs.progress)
    }

    @Test
    fun moveRefusesAnExistingTargetWithoutReplaceExisting() {
        fs.files["/a"] = MemoryFile.regular("new")
        fs.files["/b"] = MemoryFile.regular("old")
        assertThrows(FileAlreadyExistsException::class.java) { fs.move("/a", "/b", options()) }
    }

    @Test
    fun copyOfAMissingSourceFails() {
        assertThrows(NoSuchFileException::class.java) { fs.copy("/missing", "/b", options()) }
    }

    private fun options(
        replaceExisting: Boolean = false,
        atomicMove: Boolean = false
    ): CopyOptions = CopyOptions(replaceExisting, false, atomicMove, false, 0) { fs.progress += it }

    @Test
    fun replacingADirectoryTellsTheProviderItIsDeletingADirectory() {
        fs.files["/a"] = MemoryFile.directory()
        fs.files["/b"] = MemoryFile.directory()
        fs.copy("/a", "/b", options(replaceExisting = true))
        assertEquals(listOf("delete /b DIRECTORY"), fs.typedCalls)
    }

    @Test
    fun replacingAFileRenamesTheSiblingAsARegularFile() {
        fs.files["/a"] = MemoryFile.regular("new")
        fs.files["/b"] = MemoryFile.regular("old")
        fs.copy("/a", "/b", options(replaceExisting = true))
        assertEquals(listOf("rename /b.part REGULAR_FILE"), fs.typedCalls)
    }

    @Test
    fun movingADirectoryByRenameTellsTheProviderTheSourceType() {
        fs.files["/a"] = MemoryFile.directory()
        fs.move("/a", "/b", options())
        assertEquals(listOf("rename /a DIRECTORY"), fs.typedCalls)
    }

    @Test
    fun movingByCopyDeletesTheSourceWithItsType() {
        fs.files["/a"] = MemoryFile.directory()
        fs.canRenameForTest = false
        fs.move("/a", "/b", options())
        assertEquals(listOf("delete /a DIRECTORY"), fs.typedCalls)
    }

    @Test
    fun aFailedPartialWriteIsCleanedUpAsARegularFile() {
        fs.files["/a"] = MemoryFile.regular("new")
        fs.failOn = "write"
        assertThrows(IOException::class.java) { fs.copy("/a", "/b", options()) }
        assertEquals(listOf("delete /b REGULAR_FILE"), fs.typedCalls)
    }
}

private class MemoryFile(
    val type: Type,
    var content: String = "",
    var linkTarget: String = "",
    var mtime: Long = 0
) {
    enum class Type { REGULAR, DIRECTORY, LINK, OTHER }

    val isDirectory: Boolean
        get() = type == Type.DIRECTORY

    val size: Long
        get() = content.length.toLong()

    companion object {
        fun regular(content: String, mtime: Long = 0) =
            MemoryFile(Type.REGULAR, content, mtime = mtime)

        fun directory() = MemoryFile(Type.DIRECTORY)

        fun link(target: String) = MemoryFile(Type.LINK, linkTarget = target)
    }
}

/** Paths are plain strings; [failOn] names the operation that throws. */
private class MemoryCopyMove : AbstractCopyMove<String, MemoryFile>() {
    val files = mutableMapOf<String, MemoryFile>()
    val log = mutableListOf<String>()
    val typedCalls = mutableListOf<String>()
    val progress = mutableListOf<Long>()
    var failOn: String? = null
    var failDeleteOf: String? = null
    var canRenameForTest = true

    override fun readAttributes(path: String, noFollowLinks: Boolean): MemoryFile =
        files[path] ?: throw NoSuchFileException(path)

    override fun readAttributesOrNull(path: String): MemoryFile? = files[path]

    override fun isSameFile(
        source: String,
        sourceAttributes: MemoryFile,
        target: String,
        targetAttributes: MemoryFile
    ): Boolean = source == target

    override fun getFileType(attributes: MemoryFile): FileType = when (attributes.type) {
        MemoryFile.Type.REGULAR -> FileType.REGULAR_FILE
        MemoryFile.Type.DIRECTORY -> FileType.DIRECTORY
        MemoryFile.Type.LINK -> FileType.SYMBOLIC_LINK
        MemoryFile.Type.OTHER -> FileType.OTHER
    }

    override fun getSize(attributes: MemoryFile): Long = attributes.size

    override fun copyRegularFile(
        source: String,
        sourceAttributes: MemoryFile,
        target: String,
        copyOptions: CopyOptions
    ) {
        log += "write $target"
        check(target !in files) { "$target exists" }
        // A partial file is what a failure leaves behind.
        files[target] = MemoryFile.regular(sourceAttributes.content.take(1))
        if (failOn == "write") {
            throw IOException("write failed")
        }
        files[target] = MemoryFile.regular(sourceAttributes.content)
        copyOptions.progressListener?.invoke(sourceAttributes.size)
    }

    override fun createDirectory(
        target: String,
        sourceAttributes: MemoryFile,
        copyOptions: CopyOptions
    ) {
        log += "mkdir $target"
        if (target in files) {
            throw FileAlreadyExistsException(target)
        }
        files[target] = MemoryFile.directory()
    }

    override fun copySymbolicLink(
        source: String,
        sourceAttributes: MemoryFile,
        target: String,
        copyOptions: CopyOptions
    ) {
        log += "symlink $target"
        if (target in files) {
            throw FileAlreadyExistsException(target)
        }
        files[target] = MemoryFile.link(sourceAttributes.linkTarget)
    }

    override fun delete(path: String) {
        log += "delete $path"
        if (path == failDeleteOf) {
            throw IOException("delete failed")
        }
        files -= path
    }

    override fun delete(path: String, fileType: FileType) {
        typedCalls += "delete $path $fileType"
        delete(path)
    }

    override fun replacementSibling(target: String): String = "$target.part"

    override val canRename: Boolean
        get() = canRenameForTest

    override fun rename(
        source: String,
        target: String,
        fileType: FileType,
        replaceExisting: Boolean
    ) {
        typedCalls += "rename $source $fileType"
        rename(source, target, replaceExisting)
    }

    override fun rename(source: String, target: String, replaceExisting: Boolean) {
        log += "rename $source $target $replaceExisting"
        if (failOn == "rename") {
            throw IOException("rename failed")
        }
        if (!replaceExisting && target in files) {
            throw FileAlreadyExistsException(target)
        }
        files[target] = files.remove(source) ?: throw NoSuchFileException(source)
    }

    override fun copyAttributes(
        source: String,
        sourceAttributes: MemoryFile,
        target: String,
        copyOptions: CopyOptions
    ) {
        files[target]?.mtime = sourceAttributes.mtime
    }
}
