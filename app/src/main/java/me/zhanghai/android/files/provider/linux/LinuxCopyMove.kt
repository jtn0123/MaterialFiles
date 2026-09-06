/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.linux

import android.system.OsConstants
import java.io.InterruptedIOException
import me.zhanghai.android.files.provider.common.AbstractCopyMove
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.CopyOptions
import me.zhanghai.android.files.provider.common.replacementSiblingName
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.linux.syscall.Constants
import me.zhanghai.android.files.provider.linux.syscall.StructStat
import me.zhanghai.android.files.provider.linux.syscall.StructTimespec
import me.zhanghai.android.files.provider.linux.syscall.Syscall
import me.zhanghai.android.files.provider.linux.syscall.SyscallException

internal object LinuxCopyMove : AbstractCopyMove<ByteString, StructStat>() {
    private const val SEND_FILE_COUNT = 8 * 1024

    private val XATTR_NAME_PREFIX_USER = "user.".toByteString()

    private val SLASH = "/".toByteString()

    override fun readAttributes(path: ByteString, noFollowLinks: Boolean): StructStat = try {
        if (noFollowLinks) Syscall.lstat(path) else Syscall.stat(path)
    } catch (e: SyscallException) {
        throw e.toFileSystemException(path.toString())
    }

    override fun readAttributesOrNull(path: ByteString): StructStat? = try {
        Syscall.lstat(path)
    } catch (e: SyscallException) {
        if (e.errno != OsConstants.ENOENT) {
            throw e.toFileSystemException(path.toString())
        }
        null
    }

    override fun isSameFile(
        source: ByteString,
        sourceAttributes: StructStat,
        target: ByteString,
        targetAttributes: StructStat
    ): Boolean = sourceAttributes.st_dev == targetAttributes.st_dev &&
        sourceAttributes.st_ino == targetAttributes.st_ino

    override fun getFileType(attributes: StructStat): FileType = when {
        OsConstants.S_ISREG(attributes.st_mode) -> FileType.REGULAR_FILE
        OsConstants.S_ISDIR(attributes.st_mode) -> FileType.DIRECTORY
        OsConstants.S_ISLNK(attributes.st_mode) -> FileType.SYMBOLIC_LINK
        else -> FileType.OTHER
    }

    override fun getSize(attributes: StructStat): Long = attributes.st_size

    override fun copyRegularFile(
        source: ByteString,
        sourceAttributes: StructStat,
        target: ByteString,
        copyOptions: CopyOptions
    ) {
        val sourceFd = try {
            Syscall.open(source, OsConstants.O_RDONLY, 0)
        } catch (e: SyscallException) {
            throw e.toFileSystemException(source.toString())
        }
        try {
            val targetFlags = OsConstants.O_WRONLY or OsConstants.O_TRUNC or
                OsConstants.O_CREAT or OsConstants.O_EXCL
            val targetFd = try {
                Syscall.open(target, targetFlags, sourceAttributes.st_mode)
            } catch (e: SyscallException) {
                e.maybeThrowInvalidFileNameException(target.toString())
                throw e.toFileSystemException(target.toString())
            }
            try {
                val progressIntervalMillis = copyOptions.progressIntervalMillis
                val progressListener = copyOptions.progressListener
                var lastProgressMillis = System.currentTimeMillis()
                var copiedSize = 0L
                while (true) {
                    val sentSize = try {
                        Syscall.sendfile(targetFd, sourceFd, null, SEND_FILE_COUNT.toLong())
                    } catch (e: SyscallException) {
                        throw e.toFileSystemException(source.toString(), target.toString())
                    }
                    if (sentSize == 0L) {
                        break
                    }
                    copiedSize += sentSize
                    throwIfInterrupted()
                    val currentTimeMillis = System.currentTimeMillis()
                    if (progressListener != null &&
                        currentTimeMillis >= lastProgressMillis + progressIntervalMillis
                    ) {
                        progressListener(copiedSize)
                        lastProgressMillis = currentTimeMillis
                        copiedSize = 0
                    }
                }
                progressListener?.invoke(copiedSize)
            } finally {
                try {
                    Syscall.close(targetFd)
                } catch (e: SyscallException) {
                    throw e.toFileSystemException(target.toString())
                }
            }
        } finally {
            try {
                Syscall.close(sourceFd)
            } catch (e: SyscallException) {
                throw e.toFileSystemException(source.toString())
            }
        }
    }

    @Throws(InterruptedIOException::class)
    private fun throwIfInterrupted() {
        if (Thread.interrupted()) {
            throw InterruptedIOException()
        }
    }

    override fun createDirectory(
        target: ByteString,
        sourceAttributes: StructStat,
        copyOptions: CopyOptions
    ) {
        try {
            Syscall.mkdir(target, sourceAttributes.st_mode)
        } catch (e: SyscallException) {
            e.maybeThrowInvalidFileNameException(target.toString())
            throw e.toFileSystemException(target.toString())
        }
    }

    override fun copySymbolicLink(
        source: ByteString,
        sourceAttributes: StructStat,
        target: ByteString,
        copyOptions: CopyOptions
    ) {
        val sourceTarget = try {
            Syscall.readlink(source)
        } catch (e: SyscallException) {
            throw e.toFileSystemException(source.toString())
        }
        try {
            Syscall.symlink(sourceTarget, target)
        } catch (e: SyscallException) {
            e.maybeThrowInvalidFileNameException(target.toString())
            throw e.toFileSystemException(target.toString())
        }
    }

    override fun delete(path: ByteString) {
        try {
            Syscall.remove(path)
        } catch (e: SyscallException) {
            if (e.errno != OsConstants.ENOENT) {
                throw e.toFileSystemException(path.toString())
            }
        }
    }

    override fun replacementSibling(target: ByteString): ByteString {
        val slashIndex = target.lastIndexOf(SLASH)
        val directory = if (slashIndex !=
            -1
        ) {
            target.substring(0, slashIndex + 1)
        } else {
            "".toByteString()
        }
        val name = if (slashIndex != -1) target.substring(slashIndex + 1) else target
        return directory + replacementSiblingName(name.toString()).toByteString()
    }

    // rename(2) replaces an existing target on its own.
    override fun rename(source: ByteString, target: ByteString, replaceExisting: Boolean) {
        try {
            Syscall.rename(source, target)
        } catch (e: SyscallException) {
            e.maybeThrowAtomicMoveNotSupportedException(source.toString(), target.toString())
            e.maybeThrowInvalidFileNameException(target.toString())
            throw e.toFileSystemException(source.toString(), target.toString())
        }
    }

    override fun copyAttributes(
        source: ByteString,
        sourceAttributes: StructStat,
        target: ByteString,
        copyOptions: CopyOptions
    ) {
        // Ownership should be copied before permissions so that special permission bits like
        // setuid work properly.
        try {
            if (copyOptions.copyAttributes) {
                Syscall.lchown(target, sourceAttributes.st_uid, sourceAttributes.st_gid)
            }
        } catch (e: SyscallException) {
            e.printStackTrace()
        }
        try {
            if (!OsConstants.S_ISLNK(sourceAttributes.st_mode)) {
                Syscall.chmod(target, sourceAttributes.st_mode)
            }
        } catch (e: SyscallException) {
            e.printStackTrace()
        }
        try {
            val times = arrayOf(
                if (copyOptions.copyAttributes) {
                    sourceAttributes.st_atim
                } else {
                    StructTimespec(0, Constants.UTIME_OMIT)
                },
                sourceAttributes.st_mtim
            )
            Syscall.lutimens(target, times)
        } catch (e: SyscallException) {
            e.printStackTrace()
        }
        try {
            val xattrNames = Syscall.llistxattr(source)
            for (xattrName in xattrNames) {
                if (!(copyOptions.copyAttributes || xattrName.startsWith(XATTR_NAME_PREFIX_USER))) {
                    continue
                }
                val xattrValue = Syscall.lgetxattr(source, xattrName)
                Syscall.lsetxattr(target, xattrName, xattrValue, 0)
            }
        } catch (e: SyscallException) {
            e.printStackTrace()
        }
    }
}
