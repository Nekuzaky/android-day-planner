package dev.nekuz.dayboard

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

object Recurrence {
    private const val DAYS_IN_WEEK = 7L

    fun occursOn(repeat: Repeat, date: LocalDate): Boolean = when (repeat) {
        Repeat.None -> false
        Repeat.Daily -> true
        Repeat.Weekdays -> isWeekday(date.dayOfWeek)
        is Repeat.Weekly -> date.dayOfWeek in repeat.days
        is Repeat.Monthly -> isValidMonthDay(repeat.day) && date.dayOfMonth == minOf(repeat.day, date.lengthOfMonth())
    }

    fun nextAfter(repeat: Repeat, date: LocalDate): LocalDate? {
        if (date.year >= Limits.MAX_YEAR) return null
        return when (repeat) {
            Repeat.None -> null
            Repeat.Daily -> date.plusDays(1)
            Repeat.Weekdays -> firstMatch(date) { isWeekday(it) }
            is Repeat.Weekly -> firstMatch(date) { it in repeat.days }
            is Repeat.Monthly -> nextMonthly(repeat.day, date)
        }
    }

    fun onOrAfter(repeat: Repeat, date: LocalDate): LocalDate = when {
        repeat == Repeat.None -> date
        occursOn(repeat, date) -> date
        else -> nextAfter(repeat, date) ?: date
    }

    fun isValid(repeat: Repeat): Boolean = when (repeat) {
        is Repeat.Weekly -> repeat.days.isNotEmpty()
        is Repeat.Monthly -> isValidMonthDay(repeat.day)
        else -> true
    }

    private fun isWeekday(day: DayOfWeek) = day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY

    private fun isValidMonthDay(day: Int) = day in 1..31

    private fun firstMatch(after: LocalDate, matches: (DayOfWeek) -> Boolean): LocalDate? {
        for (offset in 1..DAYS_IN_WEEK) {
            val candidate = after.plusDays(offset)
            if (matches(candidate.dayOfWeek)) return candidate
        }
        return null
    }

    private fun nextMonthly(day: Int, date: LocalDate): LocalDate? {
        if (!isValidMonthDay(day)) return null
        val month = YearMonth.from(date)
        val inMonth = clamped(month, day)
        return if (inMonth.isAfter(date)) inMonth else clamped(month.plusMonths(1), day)
    }

    private fun clamped(month: YearMonth, day: Int): LocalDate = month.atDay(minOf(day, month.lengthOfMonth()))
}
