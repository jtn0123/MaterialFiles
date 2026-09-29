/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import androidx.test.platform.app.InstrumentationRegistry
import coil.decode.DataSource
import com.hierynomus.smbj.auth.AuthenticationContext
import java.io.File
import java8.nio.file.Path
import me.zhanghai.android.files.provider.common.createDirectories
import me.zhanghai.android.files.provider.common.exists
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.provider.smb.client.Authority
import me.zhanghai.android.files.provider.smb.createSmbRootPath
import me.zhanghai.android.files.storage.SmbServer
import me.zhanghai.android.files.storage.SmbServerAuthenticator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.runners.model.FrameworkMethod

/**
 * Thumbnails of files on a real Samba share, read through SMBJ. Left out unless the
 * instrumentation argument `smbHost` names the server, which should only ever be a local test
 * container such as `mf-samba` (reached from the emulator at 10.0.2.2, port 4451):
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=me.zhanghai.android.files.coil.SambaThumbnailTest \
 *     -Pandroid.testInstrumentationRunnerArguments.smbHost=10.0.2.2
 * ```
 *
 * The share is opened as a guest unless `smbUser` (and `smbPassword`) are given. `smbPort` (4451),
 * `smbShare` (`share`) and `smbDirectory` (`ThumbnailTest`) can be given too; the directory holds
 * `camera.jpg`, a photo with a 320x240 thumbnail in its Exif data, `plain.jpg`, a photo without
 * one, and `clip.mp4`, a short video. Whichever of them is missing is written there first, so an
 * empty writable share is enough; files already there are only read.
 *
 * CI passes `requireFixtures=true`, which makes a missing `smbHost` a failure instead of leaving
 * the tests out, so that the Samba container not being passed on cannot go unnoticed.
 */
@RunWith(SambaThumbnailTest.Runner::class)
class SambaThumbnailTest {
    private lateinit var server: SmbServer
    private lateinit var directory: Path
    private lateinit var loading: ThumbnailLoading

    @Before
    fun setUp() {
        val host = arguments.getString(ARGUMENT_HOST)
        if (host == null && isFixtureRequired) {
            fail("requireFixtures is set but there is no $ARGUMENT_HOST argument")
        }
        assumeTrue("No $ARGUMENT_HOST argument, so no Samba server to test against", host != null)
        val port = arguments.getString("smbPort")?.toInt() ?: 4451
        val share = arguments.getString("smbShare") ?: "share"
        val directoryName = arguments.getString("smbDirectory") ?: "ThumbnailTest"
        val user = arguments.getString("smbUser")
        val authority = if (user != null) {
            Authority(host!!, port, user, null)
        } else {
            val guest = AuthenticationContext.guest()
            Authority(host!!, port, guest.username, guest.domain)
        }
        server = SmbServer(null, null, authority, arguments.getString("smbPassword") ?: "", "")
        SmbServerAuthenticator.addTransientServer(server)
        directory = authority.createSmbRootPath().resolve(share).resolve(directoryName)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        loading = ThumbnailLoading(File(context.filesDir, "samba-thumbnails"))
        writeMissingFixtures(File(context.filesDir, "samba-fixtures"))
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) {
            SmbServerAuthenticator.removeTransientServer(server)
        }
    }

    private fun writeMissingFixtures(localDirectory: File) {
        directory.createDirectories()
        localDirectory.mkdirs()
        writeIfMissing(localDirectory, "camera.jpg") {
            TestJpeg.write(it, 1600, 1200, 320, 240)
        }
        writeIfMissing(localDirectory, "plain.jpg") { TestJpeg.write(it, 1600, 1200) }
        writeIfMissing(localDirectory, "clip.mp4") { file ->
            InstrumentationRegistry.getInstrumentation().context.assets.open("clip.mp4")
                .use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        localDirectory.deleteRecursively()
    }

    private fun writeIfMissing(localDirectory: File, name: String, write: (File) -> Unit) {
        val path = directory.resolve(name)
        if (path.exists()) {
            return
        }
        val file = File(localDirectory, name)
        write(file)
        file.inputStream().use { input -> path.newOutputStream().use { input.copyTo(it) } }
    }

    @Test
    fun aListIconOfACameraPhotoIsItsEmbeddedThumbnail() {
        val path = directory.resolve("camera.jpg")

        val first = loading.loadSuccessfully(path, 96, 96)
        // The connected test task starts with no thumbnails on disk, as it installs the app anew.
        assertEquals(DataSource.NETWORK, first.dataSource)
        // The embedded thumbnail rather than the photo, which would be decoded at 256 pixels, and
        // turned upright as the photo's orientation says.
        assertEquals(320, maxOf(first.drawable.intrinsicWidth, first.drawable.intrinsicHeight))
        assertEquals(240, minOf(first.drawable.intrinsicWidth, first.drawable.intrinsicHeight))

        val second = loading.loadSuccessfully(path, 96, 96)
        assertEquals(DataSource.DISK, second.dataSource)
        // What is on disk is decoded to fit the icon.
        assertEquals(96, maxOf(second.drawable.intrinsicWidth, second.drawable.intrinsicHeight))
    }

    @Test
    fun aListIconOfAPhotoWithoutAnEmbeddedThumbnailIsDecodedFromThePhoto() {
        val path = directory.resolve("plain.jpg")

        val result = loading.loadSuccessfully(path, 96, 96)

        // Decoded at the 256 pixel step thumbnails of remote files are kept at.
        assertEquals(256, maxOf(result.drawable.intrinsicWidth, result.drawable.intrinsicHeight))
        assertEquals(DataSource.DISK, loading.loadSuccessfully(path, 96, 96).dataSource)
    }

    @Test
    fun aListIconOfAVideoIsOneOfItsFrames() {
        val path = directory.resolve("clip.mp4")

        val result = loading.loadSuccessfully(path, 96, 96)

        assertTrue(result.drawable.intrinsicWidth > 0)
        assertEquals(DataSource.DISK, loading.loadSuccessfully(path, 96, 96).dataSource)
    }

    /**
     * Leaves the tests out when no server is given and none is required. The assumption above
     * alone would skip them, but the Android test engine reports a skipped test as a failure in its
     * XML and HTML reports.
     */
    class Runner(testClass: Class<*>) : BlockJUnit4ClassRunner(testClass) {
        override fun getChildren(): List<FrameworkMethod> =
            if (arguments.getString(ARGUMENT_HOST) != null || isFixtureRequired) {
                super.getChildren()
            } else {
                emptyList()
            }
    }

    companion object {
        private const val ARGUMENT_HOST = "smbHost"

        private val arguments
            get() = InstrumentationRegistry.getArguments()

        private val isFixtureRequired: Boolean
            get() = arguments.getString("requireFixtures").toBoolean()
    }
}
