/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerDecodeSizeTest {
    @Test
    fun aLandscapePhotoFitsTwiceTheScreenTurnedSideways() {
        assertEquals(4800 to 2160, getViewerDecodeSize(4000, 3000, 0, 1080, 2400))
        // The same, whichever way the device is held.
        assertEquals(4800 to 2160, getViewerDecodeSize(4000, 3000, 0, 2400, 1080))
    }

    @Test
    fun aPortraitPhotoFitsTwiceTheScreenUpright() {
        assertEquals(2160 to 4800, getViewerDecodeSize(3000, 4000, 0, 1080, 2400))
    }

    @Test
    fun aPhotoIsMeasuredTheWayItIsShown() {
        // Cameras store portrait photos sideways and ask for them to be turned.
        assertEquals(2160 to 4800, getViewerDecodeSize(4000, 3000, 90, 1080, 2400))
        assertEquals(2160 to 4800, getViewerDecodeSize(4000, 3000, 270, 1080, 2400))
        assertEquals(4800 to 2160, getViewerDecodeSize(4000, 3000, 180, 1080, 2400))
    }

    @Test
    fun aSquarePhotoCountsAsLandscape() {
        assertEquals(4800 to 2160, getViewerDecodeSize(1000, 1000, 0, 1080, 2400))
    }
}
