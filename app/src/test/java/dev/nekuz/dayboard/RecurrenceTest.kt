package dev.nekuz.dayboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate

fun d(text: String): LocalDate = LocalDate.parse(text)

class RecurrenceTest {
    private fun chain(repeat: Repeat, start: String, count: Int): List<String> {
        val out = ArrayList<String>()
        var date = d(start)
        repeat(count) {
            date = Recurrence.nextAfter(repeat, date)!!
            out.add(date.toString())
        }
        return out
    }

    @Test
    fun noneHasNoNext() {
        assertNull(Recurrence.nextAfter(Repeat.None, d("2026-01-01")))
        assertFalse(Recurrence.occursOn(Repeat.None, d("2026-01-01")))
    }

    @Test
    fun noneOnOrAfterKeepsDate() {
        assertEquals(d("2026-01-01"), Recurrence.onOrAfter(Repeat.None, d("2026-01-01")))
    }

    @Test
    fun dailyCrossesYearBoundary() {
        assertEquals(listOf("2027-01-01", "2027-01-02"), chain(Repeat.Daily, "2026-12-31", 2))
    }

    @Test
    fun dailyCrossesLeapDay() {
        assertEquals(listOf("2028-02-29", "2028-03-01"), chain(Repeat.Daily, "2028-02-28", 2))
    }

    @Test
    fun dailyCrossesNonLeapFebruary() {
        assertEquals(listOf("2027-03-01"), chain(Repeat.Daily, "2027-02-28", 1))
    }

    @Test
    fun dailyCrossesParisDstDays() {
        assertEquals(listOf("2026-03-29", "2026-03-30"), chain(Repeat.Daily, "2026-03-28", 2))
        assertEquals(listOf("2026-10-25", "2026-10-26"), chain(Repeat.Daily, "2026-10-24", 2))
    }

    @Test
    fun weekdaysSkipWeekend() {
        assertEquals(listOf("2026-11-09", "2026-11-10"), chain(Repeat.Weekdays, "2026-11-06", 2))
    }

    @Test
    fun weekdaysFromSaturdayAndSunday() {
        assertEquals(d("2026-11-09"), Recurrence.nextAfter(Repeat.Weekdays, d("2026-11-07")))
        assertEquals(d("2026-11-09"), Recurrence.nextAfter(Repeat.Weekdays, d("2026-11-08")))
    }

    @Test
    fun weekdaysAcrossYearBoundary() {
        assertEquals(d("2027-01-01"), Recurrence.nextAfter(Repeat.Weekdays, d("2026-12-31")))
        assertEquals(d("2027-01-04"), Recurrence.nextAfter(Repeat.Weekdays, d("2027-01-01")))
    }

    @Test
    fun weekdaysOccursOnEachDay() {
        val expected = mapOf(
            "2026-11-09" to true,
            "2026-11-10" to true,
            "2026-11-11" to true,
            "2026-11-12" to true,
            "2026-11-13" to true,
            "2026-11-14" to false,
            "2026-11-15" to false,
        )
        expected.forEach { (date, occurs) -> assertEquals(date, occurs, Recurrence.occursOn(Repeat.Weekdays, d(date))) }
    }

    @Test
    fun weeklySingleDay() {
        val repeat = Repeat.Weekly(setOf(MONDAY))
        assertEquals(listOf("2026-11-09", "2026-11-16", "2026-11-23"), chain(repeat, "2026-11-02", 3))
    }

    @Test
    fun weeklyMultipleDays() {
        val repeat = Repeat.Weekly(setOf(MONDAY, WEDNESDAY, FRIDAY))
        assertEquals(
            listOf("2026-11-11", "2026-11-13", "2026-11-16", "2026-11-18"),
            chain(repeat, "2026-11-09", 4),
        )
    }

    @Test
    fun weeklyEveryDayEqualsDaily() {
        val repeat = Repeat.Weekly(DayOfWeek.entries.toSet())
        assertEquals(chain(Repeat.Daily, "2026-12-30", 5), chain(repeat, "2026-12-30", 5))
    }

    @Test
    fun weeklyNextIsStrictlyAfter() {
        val repeat = Repeat.Weekly(setOf(SUNDAY))
        assertEquals(d("2026-11-15"), Recurrence.nextAfter(repeat, d("2026-11-08")))
    }

    @Test
    fun weeklyEmptyHasNoNextAndIsInvalid() {
        val repeat = Repeat.Weekly(emptySet())
        assertNull(Recurrence.nextAfter(repeat, d("2026-01-01")))
        assertFalse(Recurrence.isValid(repeat))
    }

    @Test
    fun weeklyAcrossLeapDay() {
        assertEquals(d("2028-02-29"), Recurrence.nextAfter(Repeat.Weekly(setOf(DayOfWeek.TUESDAY)), d("2028-02-22")))
    }

    @Test
    fun monthlyJan31UsesLastDayOfShortMonthsThenReturnsTo31() {
        assertEquals(
            listOf("2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31", "2026-06-30"),
            chain(Repeat.Monthly(31), "2026-01-31", 5),
        )
    }

    @Test
    fun monthlyJan31InLeapYear() {
        assertEquals(listOf("2028-02-29", "2028-03-31"), chain(Repeat.Monthly(31), "2028-01-31", 2))
    }

    @Test
    fun monthlyFeb29AnchorStaysOn29() {
        assertEquals(
            listOf("2028-03-29", "2028-04-29", "2028-05-29"),
            chain(Repeat.Monthly(29), "2028-02-29", 3),
        )
    }

    @Test
    fun monthly29InNonLeapFebruaryClampsTo28() {
        assertEquals(listOf("2027-02-28", "2027-03-29"), chain(Repeat.Monthly(29), "2027-01-29", 2))
    }

    @Test
    fun monthly30InFebruaryClamps() {
        assertEquals(listOf("2027-02-28", "2027-03-30"), chain(Repeat.Monthly(30), "2027-01-30", 2))
    }

    @Test
    fun monthlyAcrossYearBoundary() {
        assertEquals(listOf("2027-01-15", "2027-02-15"), chain(Repeat.Monthly(15), "2026-12-15", 2))
        assertEquals(listOf("2027-01-31"), chain(Repeat.Monthly(31), "2026-12-31", 1))
    }

    @Test
    fun monthlyFromMisalignedDateGoesToSameMonth() {
        assertEquals(d("2026-03-20"), Recurrence.nextAfter(Repeat.Monthly(20), d("2026-03-05")))
        assertEquals(d("2026-04-20"), Recurrence.nextAfter(Repeat.Monthly(20), d("2026-03-20")))
        assertEquals(d("2026-04-20"), Recurrence.nextAfter(Repeat.Monthly(20), d("2026-03-25")))
    }

    @Test
    fun monthlyClampedDayInMonthIsNotRepeated() {
        assertEquals(d("2026-03-31"), Recurrence.nextAfter(Repeat.Monthly(31), d("2026-02-28")))
    }

    @Test
    fun monthlyOccursOnClampedDay() {
        assertTrue(Recurrence.occursOn(Repeat.Monthly(31), d("2026-02-28")))
        assertFalse(Recurrence.occursOn(Repeat.Monthly(31), d("2028-02-28")))
        assertTrue(Recurrence.occursOn(Repeat.Monthly(31), d("2028-02-29")))
        assertTrue(Recurrence.occursOn(Repeat.Monthly(31), d("2026-04-30")))
        assertFalse(Recurrence.occursOn(Repeat.Monthly(31), d("2026-03-30")))
    }

    @Test
    fun monthlyInvalidDayIsRejected() {
        listOf(0, -1, 32, Int.MAX_VALUE, Int.MIN_VALUE).forEach { day ->
            assertFalse(Recurrence.isValid(Repeat.Monthly(day)))
            assertNull(Recurrence.nextAfter(Repeat.Monthly(day), d("2026-01-01")))
            assertFalse(Recurrence.occursOn(Repeat.Monthly(day), d("2026-01-01")))
        }
    }

    @Test
    fun monthlyFullYearNeverSkipsAMonth() {
        for (day in 1..31) {
            val dates = chain(Repeat.Monthly(day), "2027-01-01", 24).map { d(it) }
            val months = dates.map { it.year * 12 + it.monthValue }
            for (index in 1 until months.size) assertEquals(1, months[index] - months[index - 1])
            dates.forEach { assertEquals(minOf(day, it.lengthOfMonth()), it.dayOfMonth) }
        }
    }

    @Test
    fun onOrAfterKeepsMatchingDate() {
        assertEquals(d("2026-11-09"), Recurrence.onOrAfter(Repeat.Weekly(setOf(MONDAY)), d("2026-11-09")))
    }

    @Test
    fun onOrAfterMovesToNextMatch() {
        assertEquals(d("2026-11-16"), Recurrence.onOrAfter(Repeat.Weekly(setOf(MONDAY)), d("2026-11-10")))
        assertEquals(d("2026-11-09"), Recurrence.onOrAfter(Repeat.Weekdays, d("2026-11-07")))
        assertEquals(d("2026-02-28"), Recurrence.onOrAfter(Repeat.Monthly(31), d("2026-02-10")))
    }

    @Test
    fun yearLimitStopsRecurrence() {
        assertNull(Recurrence.nextAfter(Repeat.Daily, d("2999-12-31")))
        assertNull(Recurrence.nextAfter(Repeat.Monthly(31), d("2999-12-31")))
        assertNull(Recurrence.nextAfter(Repeat.Weekdays, LocalDate.MAX))
    }

    @Test
    fun saturdayOnlyAcrossLeapYearEnd() {
        assertEquals(d("2029-01-06"), Recurrence.nextAfter(Repeat.Weekly(setOf(SATURDAY)), d("2028-12-30")))
    }
}
