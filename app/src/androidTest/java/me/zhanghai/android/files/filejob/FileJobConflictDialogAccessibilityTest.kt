/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.content.Intent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java8.nio.file.Paths
import me.zhanghai.android.files.NoRootAccessRule
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.util.putArgs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The row that shows the new name field of the conflict dialog tells TalkBack whether the field is
 * shown, and offers to expand or collapse it, whichever a tap does.
 */
@RunWith(AndroidJUnit4::class)
class FileJobConflictDialogAccessibilityTest {
    @get:Rule
    val noRootAccess = NoRootAccessRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File(context.filesDir, "conflict-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun theNewNameRowSaysWhetherItIsExpandedAndTogglesByAction() {
        val source = File(directory, "source/Notes.txt").apply {
            parentFile!!.mkdirs()
            writeText("source")
        }
        val target = File(directory, "Notes.txt").apply { writeText("target") }
        val args = FileJobConflictDialogFragment.Args(
            Paths.get(source.path).loadFileItem(),
            Paths.get(target.path).loadFileItem(),
            CopyMoveType.COPY
        ) { _, _, _ -> }
        val intent = Intent(context, FileJobConflictDialogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putArgs(args)
        ActivityScenario.launch<FileJobConflictDialogActivity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            lateinit var showNameLayout: View
            lateinit var nameLayout: View
            scenario.onActivity { activity ->
                val dialog = (
                    activity.supportFragmentManager.findFragmentByTag(
                        FileJobConflictDialogFragment::class.java.name
                    ) as FileJobConflictDialogFragment
                    ).requireDialog()
                showNameLayout = dialog.findViewById(R.id.showNameLayout)
                nameLayout = dialog.findViewById(R.id.nameLayout)
            }
            instrumentation.runOnMainSync {
                assertFalse(nameLayout.isVisible)
                assertExpanded(showNameLayout, false)

                assertTrue(
                    showNameLayout.performAccessibilityAction(
                        AccessibilityAction.ACTION_EXPAND.id,
                        null
                    )
                )
                assertTrue(nameLayout.isVisible)
                assertExpanded(showNameLayout, true)

                assertTrue(
                    showNameLayout.performAccessibilityAction(
                        AccessibilityAction.ACTION_COLLAPSE.id,
                        null
                    )
                )
                assertFalse(nameLayout.isVisible)
                assertExpanded(showNameLayout, false)
            }
        }
    }

    private fun assertExpanded(view: View, isExpanded: Boolean) {
        assertEquals(
            context.getString(
                if (isExpanded) {
                    R.string.file_job_conflict_show_name_expanded
                } else {
                    R.string.file_job_conflict_show_name_collapsed
                }
            ),
            view.stateDescription
        )
        val actionIds = view.createAccessibilityNodeInfo().actionList.map { it.id }
        assertEquals(isExpanded, AccessibilityAction.ACTION_COLLAPSE.id in actionIds)
        assertEquals(!isExpanded, AccessibilityAction.ACTION_EXPAND.id in actionIds)
    }
}
