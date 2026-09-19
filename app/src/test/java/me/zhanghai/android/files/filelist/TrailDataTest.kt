/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Parcel
import android.os.Parcelable
import me.zhanghai.android.files.provider.common.TestPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TrailDataTest {
    private class State : Parcelable {
        override fun describeContents(): Int = 0

        override fun writeToParcel(dest: Parcel, flags: Int) {}
    }

    @Test
    fun aRestoredLocationHasItsAncestorsBehindIt() {
        val trailData = TrailData.of(TestPath("/share/Media/Photos"), State())
        assertEquals(
            listOf("/", "/share", "/share/Media", "/share/Media/Photos"),
            trailData.trail.map { it.toString() }
        )
        assertEquals(TestPath("/share/Media/Photos"), trailData.currentPath)
        assertEquals(TestPath("/share/Media"), trailData.navigateUp()!!.currentPath)
    }

    @Test
    fun theRestoredStateIsPendingOnce() {
        val state = State()
        val trailData = TrailData.of(TestPath("/share/Media"), state)
        assertSame(state, trailData.pendingState)
        assertNull(trailData.pendingState)
    }

    @Test
    fun theRestoredStateBelongsToTheRestoredFolderOnly() {
        val trailData = TrailData.of(TestPath("/share/Media"), State())
        assertNull(trailData.navigateUp()!!.pendingState)
    }

    @Test
    fun withoutAStateNothingIsPending() {
        assertNull(TrailData.of(TestPath("/share/Media")).pendingState)
    }
}
