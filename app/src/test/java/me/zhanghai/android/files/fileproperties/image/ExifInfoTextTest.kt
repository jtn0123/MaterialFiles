/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExifInfoTextTest {
    private fun equipment(make: String?, model: String?): String? =
        getEquipment(make, model) { theMake, theModel -> "$theMake $theModel" }

    @Test
    fun theMakeIsNamedOnlyWhenTheModelDoesNot() {
        assertEquals("Canon EOS R5", equipment("Canon", "EOS R5"))
        assertEquals("Canon EOS R5", equipment("canon", "Canon EOS R5"))
    }

    @Test
    fun eitherHalfAloneIsTheEquipment() {
        assertEquals("Canon", equipment("Canon", null))
        assertEquals("EOS R5", equipment(null, "EOS R5"))
        assertNull(equipment(null, null))
    }

    @Test
    fun aLongExposureIsInSeconds() {
        val text = { value: Double -> getShutterSpeedText(value) { "1/$it" } }
        assertEquals("1.0", text(0.0))
        assertEquals("2.0", text(-1.0))
        assertEquals("1.4", text(-0.5))
    }

    @Test
    fun aShortExposureIsAFractionOfASecond() {
        assertEquals("1/2", getShutterSpeedText(0.5) { "1/$it" })
        assertEquals("1/257", getShutterSpeedText(8.0) { "1/$it" })
    }
}
