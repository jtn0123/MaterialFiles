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

    @Test
    fun goingDeeperKeepsTheStateOfTheFolderLeft() {
        val left = State()
        val trailData = TrailData.of(TestPath("/share")).navigateTo(left, TestPath("/share/Media"))
        assertEquals(listOf("/", "/share", "/share/Media"), trailData.trail.map { it.toString() })
        assertEquals(TestPath("/share/Media"), trailData.currentPath)
        assertNull(trailData.pendingState)
        assertSame(left, trailData.navigateUp()!!.pendingState)
    }

    @Test
    fun goingUpKeepsTheWayBackDown() {
        val photos = State()
        val media = State()
        val trailData = TrailData.of(TestPath("/share/Media/Photos"), photos)
            .navigateUp()!!
            .navigateTo(media, TestPath("/share"))
        assertEquals(
            listOf("/", "/share", "/share/Media", "/share/Media/Photos"),
            trailData.trail.map { it.toString() }
        )
        assertEquals(TestPath("/share"), trailData.currentPath)
        val back = trailData.navigateTo(State(), TestPath("/share/Media"))
        assertEquals(4, back.trail.size)
        assertSame(media, back.pendingState)
    }

    @Test
    fun goingSidewaysDropsTheOldBranch() {
        val trailData = TrailData.of(TestPath("/share/Media/Photos"))
            .navigateTo(State(), TestPath("/share/Music"))
        assertEquals(listOf("/", "/share", "/share/Music"), trailData.trail.map { it.toString() })
        assertNull(trailData.pendingState)
    }
}
