package dev.nekuz.dayboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

class BoardsTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val now = at("2026-11-07T10:00:00+01:00")
    private val today = d("2026-11-07")

    private fun at(text: String) = OffsetDateTime.parse(text).toInstant()
    private fun t(text: String) = LocalTime.parse(text)
    private fun draft(
        title: String = "Task",
        date: String = "2026-11-07",
        time: String? = null,
        remind: Boolean = false,
        repeat: Repeat = Repeat.None,
    ) = TaskDraft(title, d(date), time?.let(::t), remind, repeat)

    private fun boardWith(task: Task) = Board(nextId = task.id + 1, tasks = listOf(task))

    @Test
    fun quickAddUsesToday() {
        val task = Boards.quickAdd(Board(), "Hello", today).tasks.single()
        assertEquals(today, task.date)
        assertNull(task.time)
        assertEquals(1, task.id)
    }

    @Test
    fun quickAddCleansTitle() {
        assertEquals("ab c", Boards.quickAdd(Board(), "  a‮b\u0000 \n c ", today).tasks.single().title)
    }

    @Test
    fun quickAddRefusesBlank() {
        val board = Board()
        assertSame(board, Boards.quickAdd(board, "  ​ ", today))
    }

    @Test
    fun quickAddRespectsCap() {
        val board = Board(
            nextId = Limits.MAX_TASKS + 1,
            tasks = (1..Limits.MAX_TASKS).map { Task(it, "t", today) },
        )
        assertSame(board, Boards.quickAdd(board, "x", today))
    }

    @Test
    fun titleIsAlwaysWithinCap() {
        val task = Boards.quickAdd(Board(), "y".repeat(50_000), today).tasks.single()
        assertTrue(task.title.length <= Limits.MAX_TITLE)
    }

    @Test
    fun cleanedTitleIsStable() {
        val cleaned = Titles.clean("y".repeat(50_000), Limits.MAX_TITLE)
        assertEquals(cleaned, Titles.clean(cleaned, Limits.MAX_TITLE))
    }

    @Test
    fun cappedTitleSurvivesCodec() {
        val board = Boards.quickAdd(Board(), "y".repeat(50_000), today)
        assertEquals(board, (BoardCodec.decode(BoardCodec.encode(board)) as Decoded.Ok).board)
    }

    @Test
    fun idsIncreaseAndAreNotReusedAfterDelete() {
        val first = Boards.quickAdd(Board(), "a", today)
        val deleted = Boards.deleteTask(first, 1)
        val second = Boards.quickAdd(deleted, "b", today)
        assertEquals(2, second.tasks.single().id)
    }

    @Test
    fun exhaustedIdsRefuseAdd() {
        val board = Board(nextId = Limits.MAX_ID + 1)
        assertNull(Boards.newId(board))
        assertSame(board, Boards.quickAdd(board, "a", today))
        assertSame(board, Boards.addHabit(board, "a"))
    }

    @Test
    fun addTaskAlignsDateToRepeat() {
        val board = Boards.addTask(
            Board(),
            draft(date = "2026-11-10", repeat = Repeat.Weekly(setOf(DayOfWeek.MONDAY))),
            now,
            paris,
        )
        assertEquals(d("2026-11-16"), board.tasks.single().date)
    }

    @Test
    fun addTaskRefusesInvalid() {
        val board = Board()
        assertSame(board, Boards.addTask(board, draft(title = " "), now, paris))
        assertSame(board, Boards.addTask(board, draft(repeat = Repeat.Weekly(emptySet())), now, paris))
        assertSame(board, Boards.addTask(board, draft(repeat = Repeat.Monthly(32)), now, paris))
        assertSame(board, Boards.addTask(board, draft(date = "3000-01-01"), now, paris))
    }

    @Test
    fun reminderDroppedWithoutTime() {
        val task = Boards.addTask(Board(), draft(remind = true), now, paris).tasks.single()
        assertFalse(task.remind)
    }

    @Test
    fun futureReminderIsPlanned() {
        val task = Boards.addTask(Board(), draft(time = "18:00", remind = true), now, paris).tasks.single()
        assertNull(task.notified)
        assertEquals(LocalDateTime.of(2026, 11, 7, 18, 0), Reminders.nextOccurrence(task))
    }

    @Test
    fun pastReminderDoesNotFireImmediately() {
        val task = Boards.addTask(Board(), draft(time = "08:00", remind = true), now, paris).tasks.single()
        assertNull(Reminders.nextOccurrence(task))
    }

    @Test
    fun editToLaterTimeRearmsFiredReminder() {
        val fired = Task(1, "x", today, t("08:00"), true, notified = LocalDateTime.of(2026, 11, 7, 8, 0))
        val board = Boards.updateTask(boardWith(fired), 1, draft(time = "18:00", remind = true), now, paris)
        assertEquals(LocalDateTime.of(2026, 11, 7, 18, 0), Reminders.nextOccurrence(board.tasks.single()))
    }

    @Test
    fun editToOtherPastTimeDoesNotFire() {
        val fired = Task(1, "x", today, t("08:00"), true, notified = LocalDateTime.of(2026, 11, 7, 8, 0))
        val board = Boards.updateTask(boardWith(fired), 1, draft(time = "09:00", remind = true), now, paris)
        assertNull(Reminders.nextOccurrence(board.tasks.single()))
    }

    @Test
    fun editTitleOnlyKeepsFiredState() {
        val fired = Task(1, "x", today, t("08:00"), true, notified = LocalDateTime.of(2026, 11, 7, 8, 0))
        val board = Boards.updateTask(boardWith(fired), 1, draft(title = "y", time = "08:00", remind = true), now, paris)
        assertEquals(fired.notified, board.tasks.single().notified)
        assertEquals("y", board.tasks.single().title)
    }

    @Test
    fun editMissingTaskIsNoOp() {
        val board = Board()
        assertSame(board, Boards.updateTask(board, 5, draft(), now, paris))
    }

    @Test
    fun deleteMissingIsNoOp() {
        val board = Board()
        assertSame(board, Boards.deleteTask(board, 5))
    }

    @Test
    fun deleteRemovesAlarmFromPlan() {
        val board = Boards.addTask(Board(), draft(time = "18:00", remind = true), now, paris)
        assertEquals(1, Reminders.plan(board.tasks, paris, Instant.EPOCH).size)
        assertTrue(Reminders.plan(Boards.deleteTask(board, 1).tasks, paris, Instant.EPOCH).isEmpty())
    }

    @Test
    fun completingOneOffRemovesAlarmFromPlan() {
        val board = Boards.addTask(Board(), draft(time = "18:00", remind = true), now, paris)
        val done = Boards.complete(board, 1, today, today)
        assertEquals(today, done.tasks.single().doneOn)
        assertTrue(Reminders.plan(done.tasks, paris, Instant.EPOCH).isEmpty())
    }

    @Test
    fun completeIsGuardedAgainstDoubleTap() {
        val task = Task(1, "x", today, repeat = Repeat.Daily)
        val once = Boards.complete(boardWith(task), 1, today, today)
        assertEquals(d("2026-11-08"), once.tasks.single().date)
        assertSame(once, Boards.complete(once, 1, today, today))
    }

    @Test
    fun completeOneOffTwiceIsNoOp() {
        val once = Boards.complete(boardWith(Task(1, "x", today)), 1, today, today)
        assertSame(once, Boards.complete(once, 1, today, today))
    }

    @Test
    fun completingOverdueDailySkipsToAfterToday() {
        val task = Task(1, "x", d("2026-11-01"), repeat = Repeat.Daily)
        val done = Boards.complete(boardWith(task), 1, d("2026-11-01"), today).tasks.single()
        assertEquals(d("2026-11-08"), done.date)
        assertEquals(today, done.lastDoneOn)
        assertEquals(d("2026-11-01"), done.previousDate)
    }

    @Test
    fun completingEarlyMovesPastOwnDate() {
        val task = Task(1, "x", d("2026-11-09"), repeat = Repeat.Weekly(setOf(DayOfWeek.MONDAY)))
        val done = Boards.complete(boardWith(task), 1, d("2026-11-09"), today).tasks.single()
        assertEquals(d("2026-11-16"), done.date)
    }

    @Test
    fun completingMonthlyJan31Sequence() {
        var board = boardWith(Task(1, "x", d("2026-01-31"), repeat = Repeat.Monthly(31)))
        val dates = ArrayList<String>()
        var day = d("2026-01-31")
        repeat(4) {
            board = Boards.complete(board, 1, day, day)
            day = board.tasks.single().date
            dates.add(day.toString())
        }
        assertEquals(listOf("2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31"), dates)
    }

    @Test
    fun completingRepeatAtYearLimitFinishesTask() {
        val task = Task(1, "x", d("2999-12-31"), repeat = Repeat.Daily)
        val done = Boards.complete(boardWith(task), 1, d("2999-12-31"), d("2999-12-31")).tasks.single()
        assertNotNull(done.doneOn)
        assertTrue(BoardCodec.decode(BoardCodec.encode(boardWith(done))) is Decoded.Ok)
    }

    @Test
    fun completingRepeatedTaskReschedulesAlarm() {
        val task = Task(1, "x", today, t("18:00"), true, Repeat.Daily)
        val done = Boards.complete(boardWith(task), 1, today, today).tasks.single()
        assertEquals(LocalDateTime.of(2026, 11, 8, 18, 0), Reminders.nextOccurrence(done))
    }

    @Test
    fun uncompleteOneOffPastDoesNotFire() {
        val task = Task(1, "x", today, t("08:00"), true, doneOn = today)
        val back = Boards.uncomplete(boardWith(task), 1, now, paris).tasks.single()
        assertNull(back.doneOn)
        assertNull(Reminders.nextOccurrence(back))
    }

    @Test
    fun uncompleteRepeatingRestoresDate() {
        val task = Task(1, "x", today, repeat = Repeat.Daily)
        val done = Boards.complete(boardWith(task), 1, today, today)
        val back = Boards.uncomplete(done, 1, now, paris).tasks.single()
        assertEquals(today, back.date)
        assertNull(back.lastDoneOn)
        assertNull(back.previousDate)
    }

    @Test
    fun uncompleteTwiceIsNoOp() {
        val task = Task(1, "x", today, repeat = Repeat.Daily)
        val once = Boards.uncomplete(Boards.complete(boardWith(task), 1, today, today), 1, now, paris)
        assertSame(once, Boards.uncomplete(once, 1, now, paris))
    }

    @Test
    fun markFiredWithinSlackDoesNotRefire() {
        val task = Task(1, "x", today, t("10:00"), true)
        val early = at("2026-11-07T09:59:30+01:00")
        assertTrue(Reminders.shouldFire(task, early, paris))
        val fired = Boards.markFired(boardWith(task), 1, early, paris).tasks.single()
        assertNull(Reminders.nextOccurrence(fired))
    }

    @Test
    fun markFiredMissingIsNoOp() {
        val board = Board()
        assertSame(board, Boards.markFired(board, 9, now, paris))
    }

    @Test
    fun prepareImportedSilencesPastReminders() {
        val past = Task(1, "x", d("2026-01-01"), t("08:00"), true)
        val future = Task(2, "y", d("2027-01-01"), t("08:00"), true)
        val board = Boards.prepareImported(Board(3, listOf(past, future)), now, paris)
        assertEquals(listOf(2), Reminders.plan(board.tasks, paris, Instant.EPOCH).map { it.taskId })
    }

    @Test
    fun clearDoneKeepsOpenAndRepeating() {
        val board = Board(
            4,
            listOf(
                Task(1, "open", today),
                Task(2, "done", today, doneOn = today),
                Task(3, "repeat", today, repeat = Repeat.Daily, lastDoneOn = today),
            ),
        )
        assertEquals(listOf(1, 3), Boards.clearDone(board).tasks.map { it.id })
    }

    @Test
    fun habitLifecycle() {
        val added = Boards.addHabit(Board(), "Read")
        val id = added.habits.single().id
        val checked = Boards.setCheckIn(added, id, today, true)
        assertEquals(listOf(today), checked.habits.single().checkIns)
        assertEquals(checked, Boards.setCheckIn(checked, id, today, true))
        assertEquals(emptyList<java.time.LocalDate>(), Boards.setCheckIn(checked, id, today, false).habits.single().checkIns)
        assertEquals("New", Boards.renameHabit(checked, id, " New ").habits.single().name)
        assertEquals(checked, Boards.renameHabit(checked, id, "   "))
        assertTrue(Boards.deleteHabit(checked, id).habits.isEmpty())
    }

    @Test
    fun habitCapAndBlank() {
        val board = Board()
        assertSame(board, Boards.addHabit(board, " "))
        val full = Board(nextId = 100, habits = (1..Limits.MAX_HABITS).map { Habit(it, "h") })
        assertSame(full, Boards.addHabit(full, "x"))
    }

    @Test
    fun checkInOutsideYearRangeIsRefused() {
        val board = Boards.addHabit(Board(), "Read")
        assertSame(board, Boards.setCheckIn(board, 1, d("1969-01-01"), true))
    }

    @Test
    fun slotLifecycle() {
        val added = Boards.addSlot(Board(), "Ranked", DayOfWeek.MONDAY, t("20:00"), t("22:00"))
        val slot = added.slots.single()
        assertEquals("Ranked", slot.title)
        val edited = Boards.updateSlot(added, slot.id, "New", DayOfWeek.TUESDAY, t("21:00"), t("01:00"))
        assertEquals(DayOfWeek.TUESDAY, edited.slots.single().day)
        assertTrue(Boards.deleteSlot(edited, slot.id).slots.isEmpty())
    }

    @Test
    fun slotValidation() {
        val board = Board()
        assertSame(board, Boards.addSlot(board, " ", DayOfWeek.MONDAY, t("20:00"), t("22:00")))
        assertSame(board, Boards.addSlot(board, "x", DayOfWeek.MONDAY, t("20:00"), t("20:00")))
        assertSame(board, Boards.addSlot(board, "x", DayOfWeek.MONDAY, t("20:00:10"), t("20:00:40")))
        val full = Board(nextId = 100, slots = (1..Limits.MAX_SLOTS).map { StreamSlot(it, "s", DayOfWeek.MONDAY, t("01:00"), t("02:00")) })
        assertSame(full, Boards.addSlot(full, "x", DayOfWeek.MONDAY, t("20:00"), t("22:00")))
    }

    @Test
    fun slotSecondsAreDropped() {
        val slot = Boards.addSlot(Board(), "x", DayOfWeek.MONDAY, t("20:00:15"), t("22:00:30")).slots.single()
        assertEquals(t("20:00"), slot.start)
        assertEquals(t("22:00"), slot.end)
    }

    @Test
    fun todaySections() {
        val clock = LocalDateTime.of(2026, 11, 7, 10, 0)
        val tasks = listOf(
            Task(1, "past day", d("2026-11-06")),
            Task(2, "past time", today, t("09:59")),
            Task(3, "now", today, t("10:00")),
            Task(4, "later", today, t("18:00")),
            Task(5, "untimed", today),
            Task(6, "tomorrow", d("2026-11-08")),
            Task(7, "done", today, doneOn = today),
            Task(8, "daily done", d("2026-11-08"), repeat = Repeat.Daily, lastDoneOn = today),
            Task(9, "daily done yesterday", d("2026-11-08"), repeat = Repeat.Daily, lastDoneOn = d("2026-11-06")),
        )
        val sections = TodayView.build(tasks, clock)
        assertEquals(listOf(1, 2), sections.overdue.map { it.id })
        assertEquals(listOf(3, 4, 5), sections.today.map { it.id })
        assertEquals(listOf(6, 8, 9), sections.upcoming.map { it.id })
        assertEquals(listOf(7, 8), sections.done.map { it.id })
    }

    @Test
    fun overdueSameMinuteIsNotOverdue() {
        val clock = LocalDateTime.of(2026, 11, 7, 10, 0, 59)
        val sections = TodayView.build(listOf(Task(1, "x", today, t("10:00"))), clock)
        assertTrue(sections.overdue.isEmpty())
    }

    @Test
    fun overdueSortsOldestFirstAndUntimedLast() {
        val clock = LocalDateTime.of(2026, 11, 7, 23, 0)
        val tasks = listOf(
            Task(3, "b", today, t("09:00")),
            Task(1, "a", d("2026-11-05")),
            Task(2, "c", d("2026-11-05"), t("10:00")),
        )
        assertEquals(listOf(2, 1, 3), TodayView.build(tasks, clock).overdue.map { it.id })
    }

    @Test
    fun midnightMovesTodayToOverdue() {
        val task = Task(1, "x", today)
        assertEquals(listOf(1), TodayView.build(listOf(task), LocalDateTime.of(2026, 11, 7, 23, 59)).today.map { it.id })
        assertEquals(listOf(1), TodayView.build(listOf(task), LocalDateTime.of(2026, 11, 8, 0, 0)).overdue.map { it.id })
    }

    @Test
    fun format24And12Hour() {
        assertEquals("09:05", Format.time(t("09:05"), true))
        assertEquals("9:05 AM", Format.time(t("09:05"), false))
        assertEquals("12:00 AM", Format.time(t("00:00"), false))
        assertEquals("12:00 PM", Format.time(t("12:00"), false))
        assertEquals("11:59 PM", Format.time(t("23:59"), false))
    }

    @Test
    fun formatDateAndRepeat() {
        assertEquals("Tue 29 Feb 2028", Format.date(d("2028-02-29")))
        assertEquals("", Format.repeat(Repeat.None))
        assertEquals("Daily", Format.repeat(Repeat.Daily))
        assertEquals("Weekdays", Format.repeat(Repeat.Weekdays))
        assertEquals("Weekly: Mon, Sun", Format.repeat(Repeat.Weekly(setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY))))
        assertEquals("Monthly on day 31", Format.repeat(Repeat.Monthly(31)))
    }

    @Test
    fun taskMetaParts() {
        val task = Task(1, "x", d("2026-11-08"), t("09:00"), true, Repeat.Daily)
        assertEquals("Sun 8 Nov 2026 · 09:00 · Daily · Reminder", Format.taskMeta(task, today, true))
        assertEquals("", Format.taskMeta(Task(1, "x", today), today, true))
    }

    @Test
    fun systemEventsAcceptOnlyExactActions() {
        SystemEvents.ACCEPTED.forEach { assertTrue(it, SystemEvents.isAccepted(it)) }
        listOf(
            null,
            "",
            "android.intent.action.BOOT_COMPLETED ",
            "android.intent.action.boot_completed",
            "BOOT_COMPLETED",
            "android.intent.action.MAIN",
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.PACKAGE_REPLACED",
            ReminderIntent.ACTION,
        ).forEach { assertFalse("$it", SystemEvents.isAccepted(it)) }
    }

    @Test
    fun reminderIntentValidation() {
        assertEquals(5, ReminderIntent.validId(ReminderIntent.ACTION, 5))
        assertNull(ReminderIntent.validId(ReminderIntent.ACTION, ReminderIntent.MISSING_ID))
        assertNull(ReminderIntent.validId(ReminderIntent.ACTION, 0))
        assertNull(ReminderIntent.validId(ReminderIntent.ACTION, Limits.MAX_ID + 1))
        assertNull(ReminderIntent.validId(ReminderIntent.ACTION, Int.MIN_VALUE))
        assertNull(ReminderIntent.validId("other", 5))
        assertNull(ReminderIntent.validId(null, 5))
    }

    @Test
    fun exactAlarmActionIsAccepted() {
        assertTrue(SystemEvents.isAccepted("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"))
    }

    @Test
    fun repairClockKeepsSameBoardWhenNothingIsStale() {
        val board = boardWith(Task(1, "x", today, t("08:00"), true, notified = LocalDateTime.of(2026, 11, 7, 8, 0)))
        assertSame(board, Boards.repairClock(board, now, paris))
    }

    @Test
    fun repairClockFixesOnlyStaleTasks() {
        val fresh = Task(1, "a", today, t("08:00"), true, notified = LocalDateTime.of(2026, 11, 7, 8, 0))
        val stale = Task(2, "b", today, t("09:00"), true, Repeat.Daily, notified = LocalDateTime.of(2030, 1, 1, 9, 0))
        val board = Boards.repairClock(Board(3, listOf(fresh, stale)), now, paris)
        assertEquals(fresh, board.tasks[0])
        assertEquals(LocalDateTime.of(2026, 11, 7, 9, 0), board.tasks[1].notified)
    }

    @Test
    fun taskAddedInSecondPassOfOverlapStillFires() {
        val newYork = ZoneId.of("America/New_York")
        val secondPass = at("2026-11-01T01:15:00-05:00")
        val task = Boards.addTask(Board(), draft(date = "2026-11-01", time = "01:45", remind = true), secondPass, newYork).tasks.single()
        assertNull(task.notified)
        val trigger = Reminders.plan(listOf(task), newYork, secondPass).single().triggerMillis
        assertEquals(at("2026-11-01T01:45:00-05:00").toEpochMilli(), trigger)
        val fired = Boards.markFired(boardWith(task), task.id, at("2026-11-01T01:45:00-05:00"), newYork).tasks.single()
        assertNull(Reminders.nextOccurrence(fired))
    }

    @Test
    fun taskAddedInFirstPassOfOverlapFiresFirstAndOnlyOnce() {
        val newYork = ZoneId.of("America/New_York")
        val firstPass = at("2026-11-01T01:15:00-04:00")
        val task = Boards.addTask(Board(), draft(date = "2026-11-01", time = "01:45", remind = true), firstPass, newYork).tasks.single()
        val trigger = Reminders.plan(listOf(task), newYork, firstPass).single().triggerMillis
        assertEquals(at("2026-11-01T01:45:00-04:00").toEpochMilli(), trigger)
        val fired = Boards.markFired(boardWith(task), task.id, at("2026-11-01T01:45:00-04:00"), newYork).tasks.single()
        assertNull(Reminders.nextOccurrence(fired))
    }

    @Test
    fun taskAddedAfterBothPassesOfOverlapIsSilent() {
        val newYork = ZoneId.of("America/New_York")
        val after = at("2026-11-01T01:50:00-05:00")
        val task = Boards.addTask(Board(), draft(date = "2026-11-01", time = "01:45", remind = true), after, newYork).tasks.single()
        assertNull(Reminders.nextOccurrence(task))
    }

    @Test
    fun zoneResolutionInGapAndOverlap() {
        assertEquals(at("2026-03-29T03:30:00+02:00"), Zones.instant(d("2026-03-29"), t("02:30"), paris))
        assertEquals(at("2026-10-25T02:30:00+02:00"), Zones.instant(d("2026-10-25"), t("02:30"), paris))
    }
}
