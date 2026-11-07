package dev.nekuz.dayboard

import java.time.LocalDate
import java.util.TreeSet

data class Streak(val current: Int, val best: Int)

object Streaks {
    fun of(checkIns: Collection<LocalDate>, today: LocalDate): Streak {
        val days = checkIns.asSequence()
            .map { it.toEpochDay() }
            .filter { it <= today.toEpochDay() }
            .toSortedSet()
        var best = 0
        var run = 0
        var previous: Long? = null
        for (day in days) {
            run = if (previous != null && day == previous + 1) run + 1 else 1
            if (run > best) best = run
            previous = day
        }
        var cursor = today.toEpochDay()
        if (cursor !in days) cursor -= 1
        var current = 0
        while (cursor in days) {
            current += 1
            cursor -= 1
        }
        return Streak(current, best)
    }

    fun withCheckIn(checkIns: List<LocalDate>, date: LocalDate, checked: Boolean): List<LocalDate> {
        val days = TreeSet(checkIns)
        if (checked) days.add(date) else days.remove(date)
        val sorted = days.toList()
        return if (sorted.size > Limits.MAX_CHECK_INS) sorted.takeLast(Limits.MAX_CHECK_INS) else sorted
    }
}
