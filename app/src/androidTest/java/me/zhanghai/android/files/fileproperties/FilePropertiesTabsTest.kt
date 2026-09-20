/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.viewpager.widget.ViewPager
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import java8.nio.file.Paths
import me.zhanghai.android.files.NoRootAccessRule
import me.zhanghai.android.files.R
import me.zhanghai.android.files.coil.TestJpeg
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.filelist.FileListFragment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What the properties dialog tells the user about a photo and about a video. */
@RunWith(AndroidJUnit4::class)
class FilePropertiesTabsTest {
    @get:Rule
    val noRootAccess = NoRootAccessRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var directory: File
    private lateinit var properties: PropertiesDialogTesting

    @Before
    fun setUp() {
        executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        executeShellCommand(
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
        )
        directory = File(context.cacheDir, "properties-${UUID.randomUUID()}").apply { mkdirs() }
        properties = PropertiesDialogTesting(directory)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun executeShellCommand(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    @Test
    fun aPhotoTellsWhatCameraTookItAndHow() {
        val file = File(directory, "Camera.jpg")
        TestJpeg.write(file, 800, 600)
        TestJpeg.writeExif(file)

        properties.show(file).use { scenario ->
            val items = properties.openTab(
                scenario,
                R.string.file_properties_image,
                R.string.file_properties_media_dimensions
            )

            assertEquals(
                "800 × 600",
                items[context.getString(R.string.file_properties_media_dimensions)]
            )
            assertEquals(
                "Canon EOS 5D",
                items[context.getString(R.string.file_properties_image_equipment)]
            )
            assertEquals("f/2.8", items[context.getString(R.string.file_properties_image_f_number)])
            // APEX 7 is 1/128 s, which the shown approximation rounds up to 1/129.
            assertEquals(
                "1/129",
                items[context.getString(R.string.file_properties_image_shutter_speed)]
            )
            assertEquals(
                "50.00 mm",
                items[context.getString(R.string.file_properties_image_focal_length)]
            )
            assertEquals(
                "ISO 400",
                items[
                    context.getString(R.string.file_properties_image_photographic_sensitivity)
                ]
            )
            assertEquals(
                "A test photo",
                items[context.getString(R.string.file_properties_image_description)]
            )
            assertEquals(
                "Ansel",
                items[context.getString(R.string.file_properties_image_artist)]
            )
        }
    }

    @Test
    fun aPhotoTellsWhereItWasTaken() {
        val file = File(directory, "Trip.jpg")
        TestJpeg.write(file, 400, 300)
        TestJpeg.writeExif(file)

        properties.show(file).use { scenario ->
            val items = properties.openTab(
                scenario,
                R.string.file_properties_image,
                R.string.file_properties_media_coordinates
            )

            assertEquals(
                "37.800, -122.400",
                items[context.getString(R.string.file_properties_media_coordinates)]
            )
            assertEquals(
                "12.300 m",
                items[context.getString(R.string.file_properties_image_gps_altitude)]
            )
            // The address is looked up in the background, so it starts out as a placeholder.
            assertNotNull(items[context.getString(R.string.file_properties_media_address)])
        }
    }

    @Test
    fun aPhotoWithoutExifStillTellsItsSize() {
        val file = File(directory, "Plain.jpg")
        TestJpeg.write(file, 120, 240)

        properties.show(file).use { scenario ->
            val items = properties.openTab(
                scenario,
                R.string.file_properties_image,
                R.string.file_properties_media_dimensions
            )

            assertEquals(
                "120 × 240",
                items[context.getString(R.string.file_properties_media_dimensions)]
            )
            assertEquals(
                items.keys.toString(),
                null,
                items[context.getString(R.string.file_properties_image_f_number)]
            )
        }
    }

    @Test
    fun anApkTellsWhichAppItWouldInstallAndWhatItAsksFor() {
        val file = File(directory, "Installer.apk")
        File(context.applicationInfo.sourceDir).copyTo(file)

        properties.show(file).use { scenario ->
            val items = properties.openTab(
                scenario,
                R.string.file_properties_apk,
                R.string.file_properties_apk_package_name
            )

            assertEquals(
                context.packageName,
                items[context.getString(R.string.file_properties_apk_package_name)]
            )
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val version = items[context.getString(R.string.file_properties_apk_version)]
            assertTrue(version.orEmpty(), version.orEmpty().contains(packageInfo.versionName!!))

            // The number of permissions opens the list of what the app would be allowed to do.
            val permissions =
                items[context.getString(R.string.file_properties_apk_requested_permissions)]
            assertNotNull(items.keys.toString(), permissions)
            device.wait(Until.findObject(By.text(permissions!!)), 20_000)!!.click()

            assertNotNull(
                "The permission list never showed a permission name",
                device.wait(Until.findObject(By.textStartsWith("android.permission.")), 20_000)
            )
        }
    }

    @Test
    fun aFileTellsItsChecksumsAndRecognisesOneThatMatches() {
        val file = File(directory, "Notes.txt")
        file.writeText("Nothing to see")
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

        properties.show(file).use { scenario ->
            val items = properties.openTab(
                scenario,
                R.string.file_properties_checksum,
                R.string.file_properties_checksum_sha_256
            )

            assertEquals(
                sha256.uppercase(),
                items[context.getString(R.string.file_properties_checksum_sha_256)]?.uppercase()
            )

            val compareEdit = device.wait(
                Until.findObject(By.res(context.packageName, "compareEdit")),
                20_000
            )
            assertNotNull("The checksum tab never showed its compare field", compareEdit)
            compareEdit!!.text = sha256

            val match = context.getString(
                R.string.file_properties_checksum_compare_match_format,
                context.getString(R.string.file_properties_checksum_sha_256)
            )
            assertNotNull(
                "The checksum that was typed in was never recognised",
                device.wait(Until.findObject(By.text(match)), 20_000)
            )
        }
    }

    @Test
    fun aSongTellsWhatItIsAndHowLongItPlays() {
        val file = File(directory, "Tone.m4a")
        instrumentation.context.assets.open("clip.m4a").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }

        properties.show(file).use { scenario ->
            val items = properties.openTab(
                scenario,
                R.string.file_properties_audio,
                R.string.file_properties_media_title
            )

            assertEquals(
                "Test tone",
                items[context.getString(R.string.file_properties_media_title)]
            )
            assertEquals("Ansel", items[context.getString(R.string.file_properties_audio_artist)])
            assertEquals(
                "Test album",
                items[context.getString(R.string.file_properties_audio_album)]
            )
            val duration = items[context.getString(R.string.file_properties_media_duration)]
            // The tone is 5 s long, give or take the last frame.
            assertTrue(duration.orEmpty(), duration in listOf("00:04", "00:05"))
        }
    }

    @Test
    fun aVideoTellsItsSizeAndLength() {
        val file = File(directory, "Clip.mp4")
        instrumentation.context.assets.open("clip.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }

        properties.show(file).use { scenario ->
            val items = properties.openTab(
                scenario,
                R.string.file_properties_video,
                R.string.file_properties_media_dimensions
            )

            assertEquals(
                "64 × 64",
                items[context.getString(R.string.file_properties_media_dimensions)]
            )
            val duration = items[context.getString(R.string.file_properties_media_duration)]
            // The clip is 30 s long, give or take the last frame.
            assertTrue(duration.orEmpty(), duration in listOf("00:29", "00:30"))
            val bitRate = items[context.getString(R.string.file_properties_media_bit_rate)]
            assertTrue(bitRate.orEmpty(), bitRate.orEmpty().endsWith(" kbps"))
        }
    }
}
