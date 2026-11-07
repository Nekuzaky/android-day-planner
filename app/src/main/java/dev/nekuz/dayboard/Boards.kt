package dev.nekuz.dayboard

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class TodaySections(
    val overdue: List<Task>,
    val today: List<Task>,
    val upcoming: List<Task>,
    val done: List<Task>,
)

object TodayView {
    private val BY_TIME = compareBy<Task>({ it.date }, { it.time == null }, { it.time }, { it.id })

    fun build(tasks: List<Task>, now: LocalDateTime): TodaySections {
        val today = now.toLocalDate()
        val clock = now.toLocalTime()
        val overdue = ArrayList<Task>()
        val current = ArrayList<Task>()
        val upcoming = ArrayList<Task>()
        val done = ArrayList<Task>()
        for (task in tasks) {
            if (task.doneOn != null) {
                done.add(task)
                continue
            }
            if (task.repeat != Repeat.None && task.lastDoneOn == today) done.add(task)
            val time = task.time
            when {
                task.date.isBefore(today) -> overdue.add(task)
                task.date.isAfter(today) -> upcoming.add(task)
                time != null && time.isBefore(clock.withSecond(0).withNano(0)) -> overdue.add(task)
                else -> current.add(task)
            }
        }
        return TodaySections(
            overdue.sortedWith(BY_TIME),
            current.sortedWith(BY_TIME),
            upcoming.sortedWith(BY_TIME),
            done.sortedWith(compareByDescending<Task> { it.doneOn ?: it.lastDoneOn }.thenBy { it.id }),
        )
    }
}

object Boards {
    fun newId(board: Board): Int? = if (board.nextId > Limits.MAX_ID) null else board.nextId

    fun quickAdd(board: Board, rawTitle: String, today: LocalDate): Board {
        val title = Titles.clean(rawTitle, Limits.MAX_TITLE)
        if (title.isEmpty() || board.tasks.size >= Limits.MAX_TASKS) return board
        val id = newId(board) ?: return board
        return board.copy(nextId = id + 1, tasks = board.tasks + Task(id, title, today))
    }

    fun isValidDraft(draft: TaskDraft): Boolean =
        Titles.clean(draft.title, Limits.MAX_TITLE).isNotEmpty() &&
            Zones.isValidYear(draft.date) &&
            Recurrence.isValid(draft.repeat)

    fun addTask(board: Board, draft: TaskDraft, now: Instant, zone: ZoneId): Board {
        if (!isValidDraft(draft) || board.tasks.size >= Limits.MAX_TASKS) return board
        val id = newId(board) ?: return board
        val task = applyDraft(Task(id, "", draft.date), draft, now, zone)
        return board.copy(nextId = id + 1, tasks = board.tasks + task)
    }

    fun updateTask(board: Board, id: Int, draft: TaskDraft, now: Instant, zone: ZoneId): Board {
        if (!isValidDraft(draft)) return board
        return replaceTask(board, id) { applyDraft(it, draft, now, zone) }
    }

    private fun applyDraft(task: Task, draft: TaskDraft, now: Instant, zone: ZoneId): Task {
        val repeat = draft.repeat
        val time = draft.time?.withSecond(0)?.withNano(0)
        val date = Recurrence.onOrAfter(repeat, draft.date)
        val updated = task.copy(
            title = Titles.clean(draft.title, Limits.MAX_TITLE),
            date = date,
            time = time,
            remind = draft.remind && time != null,
            repeat = repeat,
            previousDate = if (repeat == Repeat.None || date != task.date) null else task.previousDate,
            lastDoneOn = if (repeat == Repeat.None) null else task.lastDoneOn,
            notified = if (date != task.date || time != task.time) earliestSafeNotified(task, date) else task.notified,
        )
        return Reminders.settleEdited(updated, now, zone)
    }

    private fun earliestSafeNotified(task: Task, date: LocalDate): LocalDateTime? {
        val notified = task.notified ?: return null
        return if (notified.toLocalDate().isBefore(date)) notified else null
    }

    fun deleteTask(board: Board, id: Int): Board {
        if (board.tasks.none { it.id == id }) return board
        return board.copy(tasks = board.tasks.filterNot { it.id == id })
    }

    fun clearDone(board: Board): Board = board.copy(tasks = board.tasks.filterNot { it.doneOn != null })

    fun complete(board: Board, id: Int, expectedDate: LocalDate, today: LocalDate): Board =
        replaceTask(board, id) { task ->
            if (task.doneOn != null || task.date != expectedDate) {
                task
            } else if (task.repeat == Repeat.None) {
                task.copy(doneOn = today)
            } else {
                val from = if (task.date.isAfter(today)) task.date else today
                val next = Recurrence.nextAfter(task.repeat, from)
                if (next == null) {
                    task.copy(doneOn = today, lastDoneOn = today)
                } else {
                    task.copy(date = next, previousDate = task.date, lastDoneOn = today)
                }
            }
        }

    fun uncomplete(board: Board, id: Int, now: Instant, zone: ZoneId): Board =
        replaceTask(board, id) { task ->
            val restored = when {
                task.repeat == Repeat.None -> task.copy(doneOn = null)
                task.previousDate != null -> task.copy(
                    date = task.previousDate,
                    previousDate = null,
                    lastDoneOn = null,
                    doneOn = null,
                )
                else -> task.copy(lastDoneOn = null, doneOn = null)
            }
            Reminders.settleEdited(restored, now, zone)
        }

    fun markFired(board: Board, id: Int, now: Instant, zone: ZoneId): Board =
        replaceTask(board, id) { Reminders.settle(it, now.plusMillis(Reminders.FIRE_SLACK_MILLIS), zone) }

    fun prepareImported(board: Board, now: Instant, zone: ZoneId): Board =
        board.copy(tasks = board.tasks.map { Reminders.settleEdited(it, now, zone) })

    fun repairClock(board: Board, now: Instant, zone: ZoneId): Board {
        val tasks = board.tasks.map { Reminders.repairClock(it, now, zone) }
        return if (tasks == board.tasks) board else board.copy(tasks = tasks)
    }

    fun addHabit(board: Board, rawName: String): Board {
        val name = Titles.clean(rawName, Limits.MAX_HABIT_NAME)
        if (name.isEmpty() || board.habits.size >= Limits.MAX_HABITS) return board
        val id = newId(board) ?: return board
        return board.copy(nextId = id + 1, habits = board.habits + Habit(id, name))
    }

    fun renameHabit(board: Board, id: Int, rawName: String): Board {
        val name = Titles.clean(rawName, Limits.MAX_HABIT_NAME)
        if (name.isEmpty()) return board
        return board.copy(habits = board.habits.map { if (it.id == id) it.copy(name = name) else it })
    }

    fun setCheckIn(board: Board, id: Int, date: LocalDate, checked: Boolean): Board {
        if (!Zones.isValidYear(date)) return board
        return board.copy(
            habits = board.habits.map {
                if (it.id == id) it.copy(checkIns = Streaks.withCheckIn(it.checkIns, date, checked)) else it
            },
        )
    }

    fun deleteHabit(board: Board, id: Int): Board = board.copy(habits = board.habits.filterNot { it.id == id })

    fun isValidSlot(rawTitle: String, start: LocalTime, end: LocalTime): Boolean =
        Titles.clean(rawTitle, Limits.MAX_SLOT_TITLE).isNotEmpty() && minuteOf(start) != minuteOf(end)

    fun addSlot(board: Board, rawTitle: String, day: DayOfWeek, start: LocalTime, end: LocalTime): Board {
        if (!isValidSlot(rawTitle, start, end) || board.slots.size >= Limits.MAX_SLOTS) return board
        val id = newId(board) ?: return board
        val slot = StreamSlot(id, Titles.clean(rawTitle, Limits.MAX_SLOT_TITLE), day, minuteOf(start), minuteOf(end))
        return board.copy(nextId = id + 1, slots = board.slots + slot)
    }

    fun updateSlot(board: Board, id: Int, rawTitle: String, day: DayOfWeek, start: LocalTime, end: LocalTime): Board {
        if (!isValidSlot(rawTitle, start, end)) return board
        val title = Titles.clean(rawTitle, Limits.MAX_SLOT_TITLE)
        return board.copy(
            slots = board.slots.map {
                if (it.id == id) it.copy(title = title, day = day, start = minuteOf(start), end = minuteOf(end)) else it
            },
        )
    }

    fun deleteSlot(board: Board, id: Int): Board = board.copy(slots = board.slots.filterNot { it.id == id })

    private fun minuteOf(time: LocalTime) = time.withSecond(0).withNano(0)

    private fun replaceTask(board: Board, id: Int, change: (Task) -> Task): Board {
        var changed = false
        val tasks = board.tasks.map {
            if (it.id != id) {
                it
            } else {
                val updated = change(it)
                if (updated != it) changed = true
                updated
            }
        }
        return if (changed) board.copy(tasks = tasks) else board
    }
}

object Format {
    private val SHORT_DAYS = mapOf(
        DayOfWeek.MONDAY to "Mon",
        DayOfWeek.TUESDAY to "Tue",
        DayOfWeek.WEDNESDAY to "Wed",
        DayOfWeek.THURSDAY to "Thu",
        DayOfWeek.FRIDAY to "Fri",
        DayOfWeek.SATURDAY to "Sat",
        DayOfWeek.SUNDAY to "Sun",
    )
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun shortDay(day: DayOfWeek): String = SHORT_DAYS.getValue(day)

    fun date(date: LocalDate): String =
        "${shortDay(date.dayOfWeek)} ${date.dayOfMonth} ${MONTHS[date.monthValue - 1]} ${date.year}"

    fun time(time: LocalTime, is24Hour: Boolean): String {
        val minute = if (time.minute < 10) "0${time.minute}" else time.minute.toString()
        if (is24Hour) {
            val hour = if (time.hour < 10) "0${time.hour}" else time.hour.toString()
            return "$hour:$minute"
        }
        val hour = if (time.hour % 12 == 0) 12 else time.hour % 12
        return "$hour:$minute ${if (time.hour < 12) "AM" else "PM"}"
    }

    fun repeat(repeat: Repeat): String = when (repeat) {
        Repeat.None -> ""
        Repeat.Daily -> "Daily"
        Repeat.Weekdays -> "Weekdays"
        is Repeat.Weekly -> "Weekly: " + repeat.days.sorted().joinToString(", ") { shortDay(it) }
        is Repeat.Monthly -> "Monthly on day ${repeat.day}"
    }

    fun taskMeta(task: Task, today: LocalDate, is24Hour: Boolean): String {
        val parts = ArrayList<String>()
        if (task.date != today) parts.add(date(task.date))
        task.time?.let { parts.add(time(it, is24Hour)) }
        if (task.repeat != Repeat.None) parts.add(repeat(task.repeat))
        if (task.remind && task.time != null) parts.add("Reminder")
        return parts.joinToString(" · ")
    }
}

object SystemEvents {
    const val BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    const val TIME_SET = "android.intent.action.TIME_SET"
    const val TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED"
    const val MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
    const val EXACT_ALARM_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    val ACCEPTED = setOf(BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED, MY_PACKAGE_REPLACED, EXACT_ALARM_CHANGED)

    fun isAccepted(action: String?): Boolean = action != null && action in ACCEPTED
}

object ReminderIntent {
    const val ACTION = "dev.nekuz.dayboard.action.REMINDER"
    const val EXTRA_TASK_ID = "taskId"
    const val MISSING_ID = -1

    fun validId(action: String?, id: Int): Int? =
        if (action == ACTION && id in 1..Limits.MAX_ID) id else null
}
