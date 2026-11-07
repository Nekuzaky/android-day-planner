package dev.nekuz.dayboard

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object Limits {
    const val MAX_TITLE = 200
    const val MAX_HABIT_NAME = 80
    const val MAX_SLOT_TITLE = 100
    const val MAX_TASKS = 2000
    const val MAX_HABITS = 50
    const val MAX_SLOTS = 50
    const val MAX_CHECK_INS = 7320
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    const val MIN_YEAR = 1970
    const val MAX_YEAR = 2999
    const val MAX_ID = 1_000_000_000
    const val MAX_EPOCH_MILLIS = 32_503_680_000_000L
    const val DISCORD_MESSAGE_LIMIT = 2000
}

sealed class Repeat {
    data object None : Repeat()

    data object Daily : Repeat()

    data object Weekdays : Repeat()

    data class Weekly(val days: Set<DayOfWeek>) : Repeat()

    data class Monthly(val day: Int) : Repeat()
}

data class Task(
    val id: Int,
    val title: String,
    val date: LocalDate,
    val time: LocalTime? = null,
    val remind: Boolean = false,
    val repeat: Repeat = Repeat.None,
    val doneOn: LocalDate? = null,
    val lastDoneOn: LocalDate? = null,
    val previousDate: LocalDate? = null,
    val notified: LocalDateTime? = null,
    val settledAt: Long? = null,
)

data class Habit(
    val id: Int,
    val name: String,
    val checkIns: List<LocalDate> = emptyList(),
)

data class StreamSlot(
    val id: Int,
    val title: String,
    val day: DayOfWeek,
    val start: LocalTime,
    val end: LocalTime,
)

data class Board(
    val nextId: Int = 1,
    val tasks: List<Task> = emptyList(),
    val habits: List<Habit> = emptyList(),
    val slots: List<StreamSlot> = emptyList(),
)

data class TaskDraft(
    val title: String,
    val date: LocalDate,
    val time: LocalTime?,
    val remind: Boolean,
    val repeat: Repeat,
)

object Titles {
    fun clean(raw: CharSequence?, max: Int): String {
        val full = ForeignText.clean(raw, max)
        return if (full.length <= max) full else ForeignText.clean(raw, max - 1)
    }
}

object Zones {
    fun instant(date: LocalDate, time: LocalTime, zone: ZoneId): Instant =
        ZonedDateTime.ofLocal(LocalDateTime.of(date, time), zone, null).toInstant()

    fun instant(occurrence: LocalDateTime, zone: ZoneId): Instant =
        ZonedDateTime.ofLocal(occurrence, zone, null).toInstant()

    fun latestInstant(occurrence: LocalDateTime, zone: ZoneId): Instant =
        ZonedDateTime.ofLocal(occurrence, zone, null).withLaterOffsetAtOverlap().toInstant()

    fun instantAfterPassedOverlap(occurrence: LocalDateTime, zone: ZoneId, now: Instant): Instant {
        val earliest = instant(occurrence, zone)
        return if (earliest.isAfter(now)) earliest else latestInstant(occurrence, zone)
    }

    fun isValidYear(date: LocalDate) = date.year in Limits.MIN_YEAR..Limits.MAX_YEAR
}
