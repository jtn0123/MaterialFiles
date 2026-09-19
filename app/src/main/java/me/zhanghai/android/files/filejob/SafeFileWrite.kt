package me.zhanghai.android.files.filejob

import java.io.IOException
import java.io.OutputStream
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.StandardCopyOption
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.provider.common.ByteStringListPath
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.deleteIfExists
import me.zhanghai.android.files.provider.common.force
import me.zhanghai.android.files.provider.common.moveTo
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.readSymbolicLinkByteString
import me.zhanghai.android.files.provider.common.replacementSibling
import me.zhanghai.android.files.provider.linux.isLinuxPath

internal fun Path.writeSafely(write: (OutputStream) -> Unit) {
    var target = this
    // Follow the leaf symlink as an ordinary save would, without replacing the link itself.
    var attributes = target.readAttributes(
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS
    )
    var links = 0
    while (attributes.isSymbolicLink) {
        if (++links > 40) throw IOException("Too many symbolic links: $this")
        val link = target as? ByteStringListPath<*>
            ?: throw IOException("Cannot safely resolve symbolic link: $this")
        target = link.resolveSibling(link.readSymbolicLinkByteString()).normalize()
        attributes =
            target.readAttributes(BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }
    if (!attributes.isRegularFile) throw IOException("Safe saving requires a regular file: $this")
    target = target.toAbsolutePath().normalize()
    val originalAttributes = attributes
    replaceTransaction(
        target,
        target.replacementSibling(),
        target.replacementSibling(),
        { stage ->
            // Copy first to preserve ownership, modes and other provider-supported attributes.
            target.copyTo(stage, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
            val stagedAttributes = stage.readAttributes(BasicFileAttributes::class.java)
            if (originalAttributes is PosixFileAttributes &&
                stagedAttributes is PosixFileAttributes &&
                (
                    originalAttributes.mode() != stagedAttributes.mode() ||
                        originalAttributes.owner() != stagedAttributes.owner() ||
                        originalAttributes.group() != stagedAttributes.group() ||
                        originalAttributes.seLinuxContext() != stagedAttributes.seLinuxContext()
                    )
            ) {
                throw IOException("Cannot safely preserve file permissions: $this")
            }
            stage.newOutputStream().use { stream ->
                write(stream)
                stream.flush()
            }
            val current = target.readAttributes(BasicFileAttributes::class.java)
            if (current.fileKey() != originalAttributes.fileKey() ||
                current.size() != originalAttributes.size() ||
                current.lastModifiedTime() != originalAttributes.lastModifiedTime()
            ) {
                throw IOException("File changed during save. Reload before saving: $this")
            }
        },
        { from, to ->
            // No REPLACE_EXISTING: if another writer created the destination, preserve both copies.
            from.moveTo(to, StandardCopyOption.ATOMIC_MOVE, LinkOption.NOFOLLOW_LINKS)
        },
        { it.deleteIfExists() },
        if (target.isLinuxPath) {
            ReplacementCommit(
                atomicReplace = { from, to ->
                    from.moveTo(
                        to,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                        LinkOption.NOFOLLOW_LINKS
                    )
                },
                forceStaged = { it.forceLocalFile() },
                forceParent = { it.parent!!.forceLocalDirectory() }
            )
        } else {
            ReplacementCommit()
        }
    )
}

private fun Path.forceLocalFile() {
    newByteChannel(StandardOpenOption.WRITE).use { it.force(true) }
}

private fun Path.forceLocalDirectory() {
    newByteChannel(StandardOpenOption.READ).use { it.force(true) }
}
