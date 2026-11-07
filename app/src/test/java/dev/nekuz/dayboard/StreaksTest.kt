package dev.nekuz.dayboard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StreaksTest {
    private fun run(end: String, length: Int): List<LocalDate> =
        (0L until length).map { d(end).minusDays(it) }

    @Test
    fun emptyIsZero() {
        assertEquals(Streak(0, 0), Streaks.of(emptyList(), d("2026-01-01")))
    }

    @Test
    fun singleToday() {
        assertEquals(Streak(1, 1), Streaks.of(listOf(d("2026-01-01")), d("2026-01-01")))
    }

    @Test
    fun yesterdayKeepsStreakAlive() {
        assertEquals(Streak(3, 3), Streaks.of(run("2026-01-01", 3), d("2026-01-02")))
    }

    @Test
    fun twoDaysAgoBreaksStreak() {
        assertEquals(Streak(0, 3), Streaks.of(run("2026-01-01", 3), d("2026-01-03")))
    }

    @Test
    fun bestIsFromOlderRun() {
        val days = run("2026-01-10", 5) + run("2026-02-10", 2)
        assertEquals(Streak(2, 5), Streaks.of(days, d("2026-02-10")))
    }

    @Test
    fun acrossYearBoundary() {
        val days = run("2027-01-02", 5)
        assertEquals(Streak(5, 5), Streaks.of(days, d("2027-01-02")))
    }

    @Test
    fun acrossLeapDay() {
        val days = listOf(d("2028-02-28"), d("2028-02-29"), d("2028-03-01"))
        assertEquals(Streak(3, 3), Streaks.of(days, d("2028-03-01")))
    }

    @Test
    fun missingLeapDayBreaksStreak() {
        val days = listOf(d("2028-02-28"), d("2028-03-01"))
        assertEquals(Streak(1, 1), Streaks.of(days, d("2028-03-01")))
    }

    @Test
    fun nonLeapFebruaryContinuesIntoMarch() {
        val days = listOf(d("2027-02-28"), d("2027-03-01"))
        assertEquals(Streak(2, 2), Streaks.of(days, d("2027-03-01")))
    }

    @Test
    fun acrossParisSpringForward() {
        assertEquals(Streak(3, 3), Streaks.of(run("2026-03-30", 3), d("2026-03-30")))
    }

    @Test
    fun acrossParisFallBack() {
        assertEquals(Streak(3, 3), Streaks.of(run("2026-10-26", 3), d("2026-10-26")))
    }

    @Test
    fun checkInsAtEdgesOfShortDstDayCountAsOneDayEach() {
        val zone = ZoneId.of("Europe/Paris")
        val days = listOf(
            "2026-03-28T23:30:00Z",
            "2026-03-29T21:59:00Z",
            "2026-03-29T22:30:00Z",
        ).map { Instant.parse(it).atZone(zone).toLocalDate() }
        assertEquals(listOf(d("2026-03-29"), d("2026-03-29"), d("2026-03-30")), days)
        assertEquals(Streak(2, 2), Streaks.of(days, d("2026-03-30")))
    }

    @Test
    fun timeZoneTravelWestWithFutureCheckInDoesNotInflateToday() {
        val tokyoToday = d("2026-03-02")
        val losAngelesToday = d("2026-03-01")
        val days = listOf(d("2026-02-28"), d("2026-03-01"), tokyoToday)
        assertEquals(Streak(2, 2), Streaks.of(days, losAngelesToday))
        assertEquals(Streak(3, 3), Streaks.of(days, tokyoToday))
    }

    @Test
    fun timeZoneTravelEastSkippingCalendarDayShowsYesterdayGrace() {
        val days = listOf(d("2026-03-01"))
        assertEquals(Streak(1, 1), Streaks.of(days, d("2026-03-02")))
        assertEquals(Streak(0, 1), Streaks.of(days, d("2026-03-03")))
    }

    @Test
    fun duplicatesAndDisorderAreHandled() {
        val days = listOf(d("2026-01-02"), d("2026-01-01"), d("2026-01-02"), d("2026-01-03"), d("2026-01-01"))
        assertEquals(Streak(3, 3), Streaks.of(days, d("2026-01-03")))
    }

    @Test
    fun longRun() {
        assertEquals(Streak(4000, 4000), Streaks.of(run("2030-06-15", 4000), d("2030-06-15")))
    }

    @Test
    fun withCheckInAddsAndSorts() {
        assertEquals(
            listOf(d("2026-01-01"), d("2026-01-02")),
            Streaks.withCheckIn(listOf(d("2026-01-02")), d("2026-01-01"), true),
        )
    }

    @Test
    fun withCheckInIsIdempotent() {
        val once = Streaks.withCheckIn(emptyList(), d("2026-01-01"), true)
        assertEquals(once, Streaks.withCheckIn(once, d("2026-01-01"), true))
    }

    @Test
    fun withCheckInRemoves() {
        assertEquals(emptyList<LocalDate>(), Streaks.withCheckIn(listOf(d("2026-01-01")), d("2026-01-01"), false))
    }

    @Test
    fun removingMissingIsNoOp() {
        val days = listOf(d("2026-01-01"))
        assertEquals(days, Streaks.withCheckIn(days, d("2026-01-02"), false))
    }

    @Test
    fun capKeepsNewest() {
        val days = (0L until Limits.MAX_CHECK_INS).map { d("2000-01-01").plusDays(it) }
        val next = d("2000-01-01").plusDays(Limits.MAX_CHECK_INS.toLong())
        val result = Streaks.withCheckIn(days, next, true)
        assertEquals(Limits.MAX_CHECK_INS, result.size)
        assertEquals(next, result.last())
        assertEquals(d("2000-01-02"), result.first())
    }
}
