/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import me.zhanghai.android.files.NoRootAccessRule
import me.zhanghai.android.files.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What the permissions tab shows for a file, and what each of its values opens. */
@RunWith(AndroidJUnit4::class)
class FilePropertiesPermissionTest {
    @get:Rule
    val noRootAccess = NoRootAccessRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var directory: File
    private lateinit var file: File
    private lateinit var properties: PropertiesDialogTesting

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        directory = File(context.cacheDir, "permissions-${UUID.randomUUID()}").apply { mkdirs() }
        file = File(directory, "Notes.txt").apply { writeText("Nothing to see") }
        properties = PropertiesDialogTesting(directory)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun permissionItems(): Map<String, String> = properties.openTab(
        properties.show(file),
        R.string.file_properties_permission,
        R.string.file_properties_permission_owner
    )

    private fun tapValue(value: String) {
        val item = device.wait(Until.findObject(By.text(value)), TIMEOUT_MILLIS)
        assertNotNull("The permissions tab never showed $value", item)
        item!!.click()
    }

    private fun awaitDialog(titleRes: Int) {
        assertNotNull(
            "The ${context.getString(titleRes)} dialog never opened",
            device.wait(
                Until.findObject(By.text(context.getString(titleRes))),
                TIMEOUT_MILLIS
            )
        )
    }

    @Test
    fun theOwnerOfAFileIsShownAndCanBeChosenFromTheUsersOnTheDevice() {
        val items = permissionItems()

        val owner = items[context.getString(R.string.file_properties_permission_owner)]
        assertNotNull(items.keys.toString(), owner)
        // A file this app wrote belongs to this app.
        assertTrue(owner.orEmpty(), owner.orEmpty().contains(Process.myUid().toString()))

        tapValue(owner!!)
        awaitDialog(R.string.file_properties_permission_set_owner_title)

        // The user the file belongs to is one of the users offered.
        assertTrue(
            "The owner was not among the users listed",
            device.wait(Until.findObjects(By.text(owner)), TIMEOUT_MILLIS).size >= 1
        )
        device.pressBack()
    }

    @Test
    fun theGroupOfAFileOpensTheGroupsOnTheDevice() {
        val items = permissionItems()

        val group = items[context.getString(R.string.file_properties_permission_group)]
        assertNotNull(items.keys.toString(), group)

        tapValue(group!!)
        awaitDialog(R.string.file_properties_permission_set_group_title)

        device.pressBack()
    }

    @Test
    fun filteringTheUsersLeavesOnlyTheOnesThatMatch() {
        val items = permissionItems()
        val owner = items[context.getString(R.string.file_properties_permission_owner)]!!

        tapValue(owner)
        awaitDialog(R.string.file_properties_permission_set_owner_title)
        device.wait(Until.findObject(By.res(context.packageName, "principalText")), TIMEOUT_MILLIS)
        val users = device.findObjects(By.res(context.packageName, "principalText"))
        assertTrue("The user list showed ${users.size} users", users.size > 1)

        val filter = device.wait(
            Until.findObject(By.res(context.packageName, "filterEdit")),
            TIMEOUT_MILLIS
        )
        assertNotNull("The user list never showed its filter", filter)
        val uid = Process.myUid().toString()
        filter!!.text = uid

        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        var remaining = users
        while (System.currentTimeMillis() < deadline && remaining.size != 1) {
            Thread.sleep(200)
            remaining = device.findObjects(By.res(context.packageName, "principalText"))
        }
        assertEquals(
            "Only the user that was filtered for should be left: " +
                remaining.map { it.text },
            1,
            remaining.size
        )
        assertTrue(remaining.single().text, uid in remaining.single().text)
        device.pressBack()
    }

    @Test
    fun theSeLinuxContextOfAFileOpensAnEditorHoldingIt() {
        val items = permissionItems()

        val seLinuxContext =
            items[context.getString(R.string.file_properties_permission_selinux_context)]
        assertNotNull(items.keys.toString(), seLinuxContext)
        assertTrue(seLinuxContext.orEmpty(), seLinuxContext.orEmpty().contains(":"))

        tapValue(seLinuxContext!!)
        awaitDialog(R.string.file_properties_permission_set_selinux_context_title)

        val edit = device.wait(
            Until.findObject(By.res(context.packageName, "seLinuxContextEdit")),
            TIMEOUT_MILLIS
        )
        assertNotNull("The SELinux context dialog never showed its field", edit)
        assertEquals(seLinuxContext, edit!!.text)
        device.pressBack()
    }

    companion object {
        private const val TIMEOUT_MILLIS = 20_000L
    }
}
