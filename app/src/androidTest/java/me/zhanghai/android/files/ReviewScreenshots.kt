package me.zhanghai.android.files

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.Assert.assertTrue

/** Opt-in captures from the real emulator UI for pull-request review. */
internal fun captureReviewScreenshot(name: String) {
    val arguments = InstrumentationRegistry.getArguments()
    if (arguments.getString("captureReviewScreenshots") != "true") return
    val directory = File(checkNotNull(arguments.getString("additionalTestOutputDir")))
    check(directory.isDirectory || directory.mkdirs())
    assertTrue(
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .takeScreenshot(File(directory, "$name.png"))
    )
}
