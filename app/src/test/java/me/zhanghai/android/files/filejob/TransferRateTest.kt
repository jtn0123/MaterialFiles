/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import me.zhanghai.android.files.provider.common.TestPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TransferRateTest {
    private var nowMillis = 1_000L
    private val rate = TransferRate { nowMillis }

    @Test
    fun theSpeedIsUnknownUntilTwoSamples() {
        assertNull(rate.bytesPerSecond)
        rate.update(0)
        assertNull(rate.bytesPerSecond)
        assertNull(rate.remainingSeconds(1_000))
    }

    @Test
    fun theFirstSpeedIsTheFirstSample() {
        rate.update(0)
        nowMillis += 500
        rate.update(1_000_000)
        assertEquals(2_000_000.0, rate.bytesPerSecond!!, 0.001)
        assertEquals(5L, rate.remainingSeconds(10_000_000))
    }

    @Test
    fun laterSamplesAreSmoothed() {
        rate.update(0)
        nowMillis += 1_000
        rate.update(1_000)
        nowMillis += 1_000
        // A burst to 11,000 bytes per second only moves the speed part of the way.
        rate.update(12_000)
        assertEquals(1_000 + TransferRate.SMOOTHING * 10_000, rate.bytesPerSecond!!, 0.001)
    }

    @Test
    fun aSampleWithNoTimePassedIsIgnored() {
        rate.update(0)
        nowMillis += 1_000
        rate.update(1_000)
        rate.update(5_000)
        assertEquals(1_000.0, rate.bytesPerSecond!!, 0.001)
    }

    @Test
    fun aSizeThatShrinksCountsAsNoProgress() {
        rate.update(1_000)
        nowMillis += 1_000
        rate.update(500)
        assertEquals(0.0, rate.bytesPerSecond!!, 0.001)
        assertNull("Nothing moving has no time left", rate.remainingSeconds(1_000))
    }

    @Test
    fun nothingLeftHasNoTimeLeft() {
        rate.update(0)
        nowMillis += 1_000
        rate.update(1_000)
        assertNull(rate.remainingSeconds(0))
    }

    @Test
    fun theTimeLeftRoundsUp() {
        rate.update(0)
        nowMillis += 1_000
        rate.update(1_000)
        assertEquals(2L, rate.remainingSeconds(1_001))
    }

    @Test
    fun theDefaultClockMeasuresRealTime() {
        val rate = TransferRate()
        rate.update(0)
        Thread.sleep(20)
        rate.update(1_000)
        assertEquals(true, rate.bytesPerSecond!! > 0)
    }

    @Test
    fun secondsAreShownInFives() {
        assertEquals(RemainingTime.Seconds(5), RemainingTime.of(0))
        assertEquals(RemainingTime.Seconds(5), RemainingTime.of(1))
        assertEquals(RemainingTime.Seconds(5), RemainingTime.of(5))
        assertEquals(RemainingTime.Seconds(10), RemainingTime.of(6))
        assertEquals(RemainingTime.Seconds(55), RemainingTime.of(55))
    }

    @Test
    fun minutesRoundUp() {
        assertEquals(RemainingTime.Minutes(1), RemainingTime.of(56))
        assertEquals(RemainingTime.Minutes(1), RemainingTime.of(60))
        assertEquals(RemainingTime.Minutes(2), RemainingTime.of(61))
        assertEquals(RemainingTime.Minutes(59), RemainingTime.of(3_599))
    }

    @Test
    fun hoursKeepTheirMinutes() {
        assertEquals(RemainingTime.Hours(1, 0), RemainingTime.of(3_600))
        assertEquals(RemainingTime.Hours(1, 10), RemainingTime.of(4_200))
        assertEquals(RemainingTime.Hours(2, 0), RemainingTime.of(7_190))
    }

    @Test
    fun detailsJoinWhatIsKnown() {
        assertEquals(
            "1 MB / 2 MB · 1 MB/s · 1 s left",
            joinTransferDetails("1 MB / 2 MB", "1 MB/s", "1 s left")
        )
        assertEquals("1 / 3 · 1 MB/s", joinTransferDetails("1 / 3", "1 MB/s", null))
        assertEquals("1 / 3", joinTransferDetails("1 / 3", null, null))
        assertNull(joinTransferDetails(null, null))
    }

    @Test
    fun transfersSayWhatTheyAreAndWhereTheyGo() {
        val sources = listOf(TestPath("/a/1.txt"), TestPath("/a/2.txt"))
        val target = TestPath("/b")

        val copy = CopyFileJob(sources, target).transferResult
        assertEquals(TransferKind.COPY, copy.kind)
        assertEquals(sources, copy.sources)
        assertSame(target, copy.targetDirectory)

        val move = MoveFileJob(sources, target).transferResult
        assertEquals(TransferKind.MOVE, move.kind)
        assertSame(target, move.targetDirectory)

        val other = object : FileJob() {
            override fun run() {
                // Only its report is under test.
            }
        }
        assertNull(other.transferResult)
    }

    @Test
    fun transferInfoMeasuresItsRate() {
        val transferInfo = TransferInfo(ScanInfo(), TestPath("/b"))
        assertNull(transferInfo.rate.bytesPerSecond)
    }
}
