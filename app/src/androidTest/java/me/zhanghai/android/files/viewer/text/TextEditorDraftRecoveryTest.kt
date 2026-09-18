package me.zhanghai.android.files.viewer.text

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import me.zhanghai.android.files.captureReviewScreenshot
import me.zhanghai.android.files.provider.root.RootStrategy
import me.zhanghai.android.files.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TextEditorDraftRecoveryTest {
    @Test fun newEditorInstanceRecoversUnsavedTextFromDisk() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val file = File(context.cacheDir, "Trip notes.txt").apply {
            writeText("Trip notes\n\nRemember to book the train.")
        }
        var previous: RootStrategy? = null
        instrumentation.runOnMainSync {
            previous = Settings.ROOT_STRATEGY.value
            Settings.ROOT_STRATEGY.putValue(RootStrategy.NEVER)
        }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(file), "text/plain")
            .setClass(
                context,
                TextEditorActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ActivityScenario.launch<TextEditorActivity>(intent).use {
                val editor = device.wait(
                    Until.findObject(
                        By.clazz(
                            "android.widget.EditText"
                        ).text("Trip notes\n\nRemember to book the train.")
                    ),
                    10000
                )
                assertNotNull(editor)
                captureReviewScreenshot("draft-original")
                editor.text =
                    "Trip notes\n\nRemember to book the train.\n\nUnsaved edits:\n- Meet Alex at 9:30\n- Bring the camera"
                device.waitForIdle()
            }
            assertEquals("Trip notes\n\nRemember to book the train.", file.readText())
            ActivityScenario.launch<TextEditorActivity>(intent).use {
                assertNotNull(
                    device.wait(
                        Until.findObject(
                            By.clazz(
                                "android.widget.EditText"
                            ).text(
                                "Trip notes\n\nRemember to book the train.\n\nUnsaved edits:\n- Meet Alex at 9:30\n- Bring the camera"
                            )
                        ),
                        10000
                    )
                )
                captureReviewScreenshot("draft-recovered")
            }
        } finally {
            try {
                kotlinx.coroutines.runBlocking {
                    TextDraftSession(
                        TextDraftStore(
                            File(context.noBackupFilesDir, "editor-drafts"),
                            java8.nio.file.Paths.get(file.path).toUri().toString()
                        )
                    ) { throw it }.read()
                }
                TextDraftStore(
                    File(context.noBackupFilesDir, "editor-drafts"),
                    file.toURI().toString()
                ).apply { read() }.clear()
                // Android's URI form may differ from java.io.File's URI form.
                TextDraftStore(
                    File(context.noBackupFilesDir, "editor-drafts"),
                    java8.nio.file.Paths.get(file.path).toUri().toString()
                ).apply { read() }.clear()
                file.delete()
            } finally {
                instrumentation.runOnMainSync {
                    previous?.let { Settings.ROOT_STRATEGY.putValue(it) }
                }
            }
        }
    }

    @Test fun recoveredDraftRemainsVisibleAfterRotationWhenFileIsMissing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val file = File(context.cacheDir, "Missing notes.txt")
        val store = TextDraftStore(
            File(context.noBackupFilesDir, "editor-drafts"),
            java8.nio.file.Paths.get(file.path).toUri().toString()
        )
        store.read()
        store.write(TextDraft("Recovered without the original", "UTF-8", 0, 0))
        var previous: RootStrategy? = null
        instrumentation.runOnMainSync {
            previous = Settings.ROOT_STRATEGY.value
            Settings.ROOT_STRATEGY.putValue(RootStrategy.NEVER)
        }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(file), "text/plain")
            .setClass(context, TextEditorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ActivityScenario.launch<TextEditorActivity>(intent).use { scenario ->
                assertNotNull(
                    device.wait(
                        Until.findObject(
                            By.clazz(
                                "android.widget.EditText"
                            ).text("Recovered without the original")
                        ),
                        10000
                    )
                )
                scenario.recreate()
                assertNotNull(
                    device.wait(
                        Until.findObject(
                            By.clazz(
                                "android.widget.EditText"
                            ).text("Recovered without the original")
                        ),
                        10000
                    )
                )
            }
        } finally {
            try {
                // Drain queued lifecycle writes before deleting this fixture's draft.
                kotlinx.coroutines.runBlocking {
                    TextDraftSession(store) { throw it }.read()
                }
                store.read()
                store.clear()
            } finally {
                instrumentation.runOnMainSync {
                    previous?.let { Settings.ROOT_STRATEGY.putValue(it) }
                }
            }
        }
    }
}
