package io.github.rwxzxy.guard.core

import io.github.rwxzxy.guard.core.appops.AndroidDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidDurationTest {

    @Test
    fun parsesZero() {
        assertEquals(0L, AndroidDuration.parseMillis("0"))
    }

    @Test
    fun parsesMillisOnly() {
        assertEquals(5L, AndroidDuration.parseMillis("+5ms"))
        assertEquals(999L, AndroidDuration.parseMillis("+999ms"))
    }

    @Test
    fun parsesSecondsAndMillis() {
        assertEquals(12_345L, AndroidDuration.parseMillis("+12s345ms"))
    }

    @Test
    fun parsesMinutesWithoutConfusingMillisSuffix() {
        // "3m4s5ms"：m 后面跟数字是分钟，"5ms" 是毫秒
        assertEquals(3 * 60_000L + 4_000 + 5, AndroidDuration.parseMillis("+3m4s5ms"))
        assertEquals(60_000L, AndroidDuration.parseMillis("+1m0s0ms"))
    }

    @Test
    fun parsesHoursWithZeroFields() {
        // TimeUtils 一旦输出了前面的字段，后面的 0 也会输出
        assertEquals(3_600_000L + 3_004, AndroidDuration.parseMillis("+1h0m3s4ms"))
    }

    @Test
    fun parsesDays() {
        val expected = 3 * 86_400_000L + 4 * 3_600_000L + 5 * 60_000L + 6_000 + 7
        assertEquals(expected, AndroidDuration.parseMillis("+3d4h5m6s7ms"))
        assertEquals(86_400_000L, AndroidDuration.parseMillis("+1d0h0m0s0ms"))
        assertEquals(1200 * 86_400_000L, AndroidDuration.parseMillis("+1200d0h0m0s0ms"))
    }

    @Test
    fun parsesNegative() {
        assertEquals(-1_500L, AndroidDuration.parseMillis("-1s500ms"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(AndroidDuration.parseMillis("abc"))
        assertNull(AndroidDuration.parseMillis("+5"))
        assertNull(AndroidDuration.parseMillis("5ms"))
        assertNull(AndroidDuration.parseMillis(""))
    }

    @Test
    fun formatRoundTrips() {
        val samples = listOf(1L, 999L, 1_000L, 59_999L, 60_000L, 3_600_000L, 86_400_000L, 90_061_001L, 400L * 86_400_000L + 7)
        for (s in samples) {
            assertEquals("round trip $s", s, AndroidDuration.parseMillis(AndroidDuration.format(s)))
        }
        assertEquals("+1h0m3s4ms", AndroidDuration.format(3_603_004L))
        assertEquals("+5ms", AndroidDuration.format(5L))
        assertEquals("0", AndroidDuration.format(0L))
    }
}
