/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package android.system

/**
 * The Linux constants, for the JVM the unit tests run on.
 *
 * The platform class takes its values from the kernel headers when the runtime starts, so the
 * `android.jar` the tests compile against declares every one of them without a value and throws
 * from every method. Code that asks what a mode means - a file's type, the permissions a new file
 * is created with - then reads zeroes, and a file created on a real server ends up with no
 * permissions at all. These are the values of a Linux kernel, which is what Android runs and what
 * the servers the tests talk to run.
 *
 * @see <a href="https://developer.android.com/r/studio-ui/build/not-mocked">Unit testing
 *   limitations</a>
 */
object OsConstants {
    const val AF_UNIX = 1
    const val SOCK_STREAM = 1
    const val POLLIN = 0x0001

    const val EPERM = 1
    const val ENOENT = 2
    const val EINTR = 4
    const val EIO = 5
    const val EBADF = 9
    const val EAGAIN = 11
    const val EACCES = 13
    const val EEXIST = 17
    const val EXDEV = 18
    const val ENOTDIR = 20
    const val EISDIR = 21
    const val EINVAL = 22
    const val EROFS = 30
    const val ENOTEMPTY = 39
    const val ELOOP = 40
    const val ENODATA = 61

    const val F_OK = 0
    const val X_OK = 1
    const val W_OK = 2
    const val R_OK = 4

    const val F_GETFL = 3
    const val F_SETFL = 4

    const val SEEK_SET = 0
    const val SEEK_CUR = 1
    const val SEEK_END = 2

    const val O_RDONLY = 0
    const val O_WRONLY = 1
    const val O_RDWR = 2
    const val O_ACCMODE = 3
    const val O_CREAT = 0x40
    const val O_EXCL = 0x80
    const val O_TRUNC = 0x200
    const val O_APPEND = 0x400
    const val O_NONBLOCK = 0x800
    const val O_DSYNC = 0x1000
    const val O_NOFOLLOW = 0x20000
    const val O_SYNC = 0x101000

    // Hexadecimal because Kotlin has no octal literals; the comments are the familiar form.
    const val S_IFMT = 0xF000 // 0170000
    const val S_IFSOCK = 0xC000 // 0140000
    const val S_IFLNK = 0xA000 // 0120000
    const val S_IFREG = 0x8000 // 0100000
    const val S_IFBLK = 0x6000 // 0060000
    const val S_IFDIR = 0x4000 // 0040000
    const val S_IFCHR = 0x2000 // 0020000
    const val S_IFIFO = 0x1000 // 0010000

    const val S_ISUID = 0x800 // 04000
    const val S_ISGID = 0x400 // 02000
    const val S_ISVTX = 0x200 // 01000

    const val S_IRWXU = 0x1C0 // 0700
    const val S_IRUSR = 0x100 // 0400
    const val S_IWUSR = 0x80 // 0200
    const val S_IXUSR = 0x40 // 0100

    const val S_IRWXG = 0x38 // 070
    const val S_IRGRP = 0x20 // 040
    const val S_IWGRP = 0x10 // 020
    const val S_IXGRP = 0x8 // 010

    const val S_IRWXO = 0x7 // 07
    const val S_IROTH = 0x4 // 04
    const val S_IWOTH = 0x2 // 02
    const val S_IXOTH = 0x1 // 01

    @JvmStatic
    fun S_ISSOCK(mode: Int): Boolean = mode and S_IFMT == S_IFSOCK

    @JvmStatic
    fun S_ISLNK(mode: Int): Boolean = mode and S_IFMT == S_IFLNK

    @JvmStatic
    fun S_ISREG(mode: Int): Boolean = mode and S_IFMT == S_IFREG

    @JvmStatic
    fun S_ISBLK(mode: Int): Boolean = mode and S_IFMT == S_IFBLK

    @JvmStatic
    fun S_ISDIR(mode: Int): Boolean = mode and S_IFMT == S_IFDIR

    @JvmStatic
    fun S_ISCHR(mode: Int): Boolean = mode and S_IFMT == S_IFCHR

    @JvmStatic
    fun S_ISFIFO(mode: Int): Boolean = mode and S_IFMT == S_IFIFO
}
