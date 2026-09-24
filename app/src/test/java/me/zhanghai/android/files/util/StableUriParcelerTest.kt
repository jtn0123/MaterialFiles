/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A Uri parcelled by the platform is read back part by part and put together the way the
 * platform's own toString() does.
 */
class StableUriParcelerTest {
    @Test
    fun anOpaqueUriKeepsItsFragmentOnlyIfThereIsOne() {
        assertEquals(
            "mailto:someone@example.com",
            StableUriParceler.buildOpaqueUriString("mailto", "someone@example.com", null)
        )
        assertEquals(
            "mailto:someone@example.com",
            StableUriParceler.buildOpaqueUriString("mailto", "someone@example.com", "")
        )
        assertEquals(
            "tel:123#ext",
            StableUriParceler.buildOpaqueUriString("tel", "123", "ext")
        )
    }

    @Test
    fun aHierarchicalUriHasEveryPartItWasGiven() {
        assertEquals(
            "content://authority/tree/primary%3A/document?query=1#fragment",
            StableUriParceler.buildHierarchicalUriString(
                "content",
                "authority",
                "/tree/primary%3A/document",
                "query=1",
                "fragment"
            )
        )
    }

    @Test
    fun aHierarchicalUriLeavesOutTheMissingParts() {
        assertEquals(
            "file:///sdcard",
            StableUriParceler.buildHierarchicalUriString("file", "", "/sdcard", null, null)
        )
        assertEquals(
            "relative/path",
            StableUriParceler.buildHierarchicalUriString(null, null, "relative/path", "", "")
        )
        assertEquals(
            "",
            StableUriParceler.buildHierarchicalUriString(null, null, null, null, null)
        )
    }

    @Test
    fun aPathIsMadeAbsoluteOnlyWhenItIsNot() {
        assertEquals("/path", StableUriParceler.makeEncodedPathPartAbsolute("path"))
        assertEquals("/path", StableUriParceler.makeEncodedPathPartAbsolute("/path"))
        assertEquals("", StableUriParceler.makeEncodedPathPartAbsolute(""))
        assertNull(StableUriParceler.makeEncodedPathPartAbsolute(null))
    }
}
