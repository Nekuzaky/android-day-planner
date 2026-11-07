package dev.nekuz.dayboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

class RemindersTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val newYork = ZoneId.of("America/New_York")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun t(text: String) = LocalTime.parse(text)
    private fun at(text: String) = OffsetDateTime.parse(text).toInstant()
    private fun ldt(text: String) = LocalDateTime.parse(text)

    private fun task(
        date: String,
        time: String? = "09:00",
        repeat: Repeat = Repeat.None,
        remind: Boolean = true,
        id: Int = 1,
        notified: String? = null,
        doneOn: String? = null,
    ) = Task(
        id = id,
        title = "t",
        date = d(date),
        time = time?.let(::t),
        remind = remind,
        repeat = repeat,
        doneOn = doneOn?.let(::d),
        notified = notified?.let(::ldt),
    )

    private fun trigger(task: Task, zone: ZoneId): Instant =
        Instant.ofEpochMilli(Reminders.plan(listOf(task), zone, Instant.EPOCH).single().triggerMillis)

    @Test
    fun plainReminderUsesWallClockInZone() {
        assertEquals(at("2026-01-15T09:00:00+01:00"), trigger(task("2026-01-15"), paris))
        assertEquals(at("2026-07-15T09:00:00+02:00"), trigger(task("2026-07-15"), paris))
    }

    @Test
    fun noReminderWithoutTime() {
        assertNull(Reminders.nextOccurrence(task("2026-01-15", time = null)))
    }

    @Test
    fun noReminderWhenOff() {
        assertNull(Reminders.nextOccurrence(task("2026-01-15", remind = false)))
    }

    @Test
    fun noReminderWhenDone() {
        assertNull(Reminders.nextOccurrence(task("2026-01-15", doneOn = "2026-01-14")))
    }

    @Test
    fun springForwardGapFiresAfterTheGap() {
        assertEquals(at("2026-03-29T03:30:00+02:00"), trigger(task("2026-03-29", "02:30"), paris))
    }

    @Test
    fun gapStartExactly() {
        assertEquals(at("2026-03-29T03:00:00+02:00"), trigger(task("2026-03-29", "02:00"), paris))
    }

    @Test
    fun rightAfterGapIsUnchanged() {
        assertEquals(at("2026-03-29T03:00:00+02:00"), trigger(task("2026-03-29", "03:00"), paris))
    }

    @Test
    fun fallBackOverlapUsesFirstInstant() {
        assertEquals(at("2026-10-25T02:30:00+02:00"), trigger(task("2026-10-25", "02:30"), paris))
    }

    @Test
    fun overlapFiresOnlyOnce() {
        val first = at("2026-10-25T02:30:00+02:00")
        val second = at("2026-10-25T02:30:00+01:00")
        val fired = Reminders.settle(task("2026-10-25", "02:30"), first, paris)
        assertNull(Reminders.nextOccurrence(fired))
        assertFalse(Reminders.shouldFire(fired, second, paris))
    }

    @Test
    fun overlapDailyMovesToNextDay() {
        val first = at("2026-10-25T02:30:00+02:00")
        val fired = Reminders.settle(task("2026-10-25", "02:30", Repeat.Daily), first, paris)
        assertEquals(at("2026-10-26T02:30:00+01:00"), trigger(fired, paris))
    }

    @Test
    fun dailyAcrossSpringForwardKeepsWallClock() {
        val first = trigger(task("2026-03-28", repeat = Repeat.Daily), paris)
        val fired = Reminders.settle(task("2026-03-28", repeat = Repeat.Daily), first, paris)
        val second = trigger(fired, paris)
        assertEquals(at("2026-03-29T09:00:00+02:00"), second)
        assertEquals(23 * 3600L, second.epochSecond - first.epochSecond)
    }

    @Test
    fun dailyAcrossFallBackKeepsWallClock() {
        val first = trigger(task("2026-10-24", repeat = Repeat.Daily), paris)
        val fired = Reminders.settle(task("2026-10-24", repeat = Repeat.Daily), first, paris)
        val second = trigger(fired, paris)
        assertEquals(at("2026-10-25T09:00:00+01:00"), second)
        assertEquals(25 * 3600L, second.epochSecond - first.epochSecond)
    }

    @Test
    fun timeZoneChangeKeepsWallClockAndMovesInstant() {
        val item = task("2026-06-10")
        assertEquals(at("2026-06-10T09:00:00+02:00"), trigger(item, paris))
        assertEquals(at("2026-06-10T09:00:00-04:00"), trigger(item, newYork))
        assertEquals(at("2026-06-10T09:00:00+09:00"), trigger(item, tokyo))
    }

    @Test
    fun firedThenTravelWestDoesNotFireAgainAtSameWallClock() {
        val fired = Reminders.settle(task("2026-06-10"), at("2026-06-10T09:00:00+02:00"), paris)
        assertNull(Reminders.nextOccurrence(fired))
        assertFalse(Reminders.shouldFire(fired, at("2026-06-10T09:00:00-04:00"), newYork))
    }

    @Test
    fun unfiredThenTravelEastFiresAsMissed() {
        val item = task("2026-06-10")
        val nowInTokyo = at("2026-06-10T09:30:00+09:00")
        assertTrue(Reminders.shouldFire(item, nowInTokyo, tokyo))
        assertFalse(Reminders.shouldFire(item, nowInTokyo, paris))
    }

    @Test
    fun shouldFireAtExactInstant() {
        assertTrue(Reminders.shouldFire(task("2026-01-15"), at("2026-01-15T09:00:00+01:00"), paris))
    }

    @Test
    fun shouldFireWithinSlackOnly() {
        assertTrue(Reminders.shouldFire(task("2026-01-15"), at("2026-01-15T08:59:30+01:00"), paris))
        assertFalse(Reminders.shouldFire(task("2026-01-15"), at("2026-01-15T08:58:00+01:00"), paris))
    }

    @Test
    fun missedReminderStaysPlannedWithPastTrigger() {
        val item = task("2026-01-15")
        assertEquals(1, Reminders.plan(listOf(item), paris, Instant.EPOCH).size)
        assertTrue(Reminders.shouldFire(item, at("2026-02-01T00:00:00+01:00"), paris))
    }

    @Test
    fun settleOneOffInFutureIsNoOp() {
        val item = task("2026-01-15")
        assertEquals(item, Reminders.settle(item, at("2026-01-14T00:00:00+01:00"), paris))
    }

    @Test
    fun settleMarksPastOneOff() {
        val settled = Reminders.settle(task("2026-01-15"), at("2026-01-16T00:00:00+01:00"), paris)
        assertEquals(ldt("2026-01-15T09:00"), settled.notified)
    }

    @Test
    fun settleDailySkipsToLatestDue() {
        val settled = Reminders.settle(task("2026-01-01", repeat = Repeat.Daily), at("2026-01-20T10:00:00+01:00"), paris)
        assertEquals(ldt("2026-01-20T09:00"), settled.notified)
        assertEquals(at("2026-01-21T09:00:00+01:00"), trigger(settled, paris))
    }

    @Test
    fun settleDailyBeforeTodaysTime() {
        val settled = Reminders.settle(task("2026-01-01", repeat = Repeat.Daily), at("2026-01-20T08:00:00+01:00"), paris)
        assertEquals(ldt("2026-01-19T09:00"), settled.notified)
        assertEquals(at("2026-01-20T09:00:00+01:00"), trigger(settled, paris))
    }

    @Test
    fun settleWeeklyFindsLastChosenDay() {
        val repeat = Repeat.Weekly(setOf(DayOfWeek.MONDAY))
        val settled = Reminders.settle(task("2026-01-05", repeat = repeat), at("2026-03-04T10:00:00+01:00"), paris)
        assertEquals(ldt("2026-03-02T09:00"), settled.notified)
    }

    @Test
    fun settleMonthlyClampedAcrossShortMonth() {
        val settled = Reminders.settle(task("2026-01-31", repeat = Repeat.Monthly(31)), at("2026-03-15T10:00:00+01:00"), paris)
        assertEquals(ldt("2026-02-28T09:00"), settled.notified)
        assertEquals(at("2026-03-31T09:00:00+02:00"), trigger(settled, paris))
    }

    @Test
    fun settleYearsOfMissedIsFastAndCorrect() {
        val settled = Reminders.settle(task("1970-01-01", repeat = Repeat.Daily), at("2026-01-20T10:00:00+01:00"), paris)
        assertEquals(ldt("2026-01-20T09:00"), settled.notified)
    }

    @Test
    fun repeatingUncompletedStillRemindsNextOccurrence() {
        val fired = Reminders.settle(task("2026-01-01", repeat = Repeat.Daily), at("2026-01-01T09:00:00+01:00"), paris)
        assertEquals(d("2026-01-01"), fired.date)
        assertEquals(at("2026-01-02T09:00:00+01:00"), trigger(fired, paris))
    }

    @Test
    fun repeatingYearEnd() {
        val fired = Reminders.settle(task("2026-12-31", repeat = Repeat.Daily), at("2026-12-31T09:00:00+01:00"), paris)
        assertEquals(at("2027-01-01T09:00:00+01:00"), trigger(fired, paris))
    }

    @Test
    fun laterTimeOnNotifiedDayStillFires() {
        val item = task("2026-01-01", "17:00", Repeat.Daily, notified = "2026-01-05T09:00")
        assertEquals(ldt("2026-01-05T17:00"), Reminders.nextOccurrence(item))
    }

    @Test
    fun earlierTimeOnNotifiedDayGoesToNext() {
        val item = task("2026-01-01", "08:00", Repeat.Daily, notified = "2026-01-05T09:00")
        assertEquals(ldt("2026-01-06T08:00"), Reminders.nextOccurrence(item))
    }

    @Test
    fun repeatAtYearLimitHasNoOccurrence() {
        val item = task("2999-12-31", repeat = Repeat.Daily, notified = "2999-12-31T09:00")
        assertNull(Reminders.nextOccurrence(item))
    }

    @Test
    fun planIsSortedAndCapped() {
        val tasks = (1..500).map { task(d("2026-01-01").plusDays((500 - it).toLong()).toString(), id = it) }
        val plan = Reminders.plan(tasks, paris, Instant.EPOCH)
        assertEquals(Reminders.MAX_PLANNED, plan.size)
        assertEquals(plan.sortedBy { it.triggerMillis }, plan)
        assertEquals(500, plan.first().taskId)
    }

    @Test
    fun planTieBreaksById() {
        val plan = Reminders.plan(listOf(task("2026-01-01", id = 2), task("2026-01-01", id = 1)), paris, Instant.EPOCH)
        assertEquals(listOf(1, 2), plan.map { it.taskId })
    }

    @Test
    fun changesSetsOnlyNewOrMoved() {
        val previous = listOf(PlannedAlarm(1, 100), PlannedAlarm(2, 200))
        val planned = listOf(PlannedAlarm(1, 100), PlannedAlarm(2, 250), PlannedAlarm(3, 300))
        val changes = Reminders.changes(previous, planned, false)
        assertEquals(emptySet<Int>(), changes.cancel)
        assertEquals(listOf(PlannedAlarm(2, 250), PlannedAlarm(3, 300)), changes.set)
    }

    @Test
    fun changesCancelsRemoved() {
        val changes = Reminders.changes(listOf(PlannedAlarm(1, 100)), emptyList(), false)
        assertEquals(setOf(1), changes.cancel)
        assertTrue(changes.set.isEmpty())
    }

    @Test
    fun forceSetsEverything() {
        val planned = listOf(PlannedAlarm(1, 100))
        assertEquals(planned, Reminders.changes(planned, planned, true).set)
    }

    @Test
    fun staleNotificationsForDeletedDoneAndMoved() {
        val base = task("2026-01-01", id = 1)
        val done = task("2026-01-01", id = 2)
        val moved = task("2026-01-01", id = 3)
        val same = task("2026-01-01", id = 4)
        val off = task("2026-01-01", id = 5)
        val before = listOf(base, done, moved, same, off)
        val after = listOf(
            done.copy(doneOn = d("2026-01-01")),
            moved.copy(date = d("2026-01-02")),
            same.copy(title = "renamed"),
            off.copy(remind = false),
        )
        assertEquals(setOf(1, 2, 3, 5), Reminders.staleNotifications(before, after))
    }

    @Test
    fun unchangedDoneTasksAreNotReportedAsStale() {
        val tasks = listOf(task("2026-01-01", doneOn = "2026-01-01"))
        assertTrue(Reminders.staleNotifications(tasks, tasks).isEmpty())
    }

    @Test
    fun clockBackFromFutureRearmsDaily() {
        val faked = Reminders.settle(task("2026-01-15", repeat = Repeat.Daily), at("2030-01-01T09:00:00+01:00"), paris)
        assertEquals(ldt("2030-01-01T09:00"), faked.notified)
        val now = at("2026-01-20T08:00:00+01:00")
        val repaired = Reminders.repairClock(faked, now, paris)
        assertEquals(ldt("2026-01-19T09:00"), repaired.notified)
        assertEquals(ldt("2026-01-20T09:00"), Reminders.nextOccurrence(repaired))
    }

    @Test
    fun clockBackFromFutureRearmsOneOffStillAhead() {
        val faked = Reminders.settle(task("2026-06-01"), at("2030-01-01T09:00:00+01:00"), paris)
        val repaired = Reminders.repairClock(faked, at("2026-01-20T08:00:00+01:00"), paris)
        assertNull(repaired.notified)
        assertEquals(ldt("2026-06-01T09:00"), Reminders.nextOccurrence(repaired))
    }

    @Test
    fun clockBackOneOffDueWithinWindowIsKept() {
        val fired = task("2026-01-20", notified = "2026-01-20T09:00")
        assertEquals(fired, Reminders.repairClock(fired, at("2026-01-20T08:00:00+01:00"), paris))
    }

    @Test
    fun repairBoundaryAtExactly27Hours() {
        val now = at("2026-01-20T00:00:00+01:00")
        val inside = task("2026-01-21", notified = "2026-01-21T03:00", time = "03:00")
        assertEquals(inside, Reminders.repairClock(inside, now, paris))
        val outside = task("2026-01-21", notified = "2026-01-21T03:01", time = "03:01")
        assertNull(Reminders.repairClock(outside, now, paris).notified)
    }

    @Test
    fun repairDoesNotTouchTravelWest() {
        val fired = Reminders.settle(task("2026-06-10"), at("2026-06-10T09:00:00+02:00"), paris)
        val now = at("2026-06-10T09:00:00-04:00")
        assertEquals(fired, Reminders.repairClock(fired, now, newYork))
        assertNull(Reminders.nextOccurrence(Reminders.repairClock(fired, now, newYork)))
    }

    @Test
    fun repairDoesNotTouchWidestZoneTravel() {
        val kiritimati = ZoneId.of("Pacific/Kiritimati")
        val bakerIsland = ZoneId.of("Etc/GMT+12")
        val fired = Reminders.settle(task("2026-06-11", "23:00"), at("2026-06-11T23:00:00+14:00"), kiritimati)
        assertEquals(fired, Reminders.repairClock(fired, at("2026-06-11T09:00:00Z"), bakerIsland))
    }

    @Test
    fun repairWithoutNotifiedIsNoOp() {
        val item = task("2026-01-15")
        assertEquals(item, Reminders.repairClock(item, at("2020-01-01T00:00:00+01:00"), paris))
    }

    @Test
    fun repairIsIdempotent() {
        val faked = task("2026-01-15", repeat = Repeat.Daily, notified = "2030-01-01T09:00")
        val now = at("2026-01-20T08:00:00+01:00")
        val once = Reminders.repairClock(faked, now, paris)
        assertEquals(once, Reminders.repairClock(once, now, paris))
    }

    @Test
    fun alarmInFirstPassMarksTheOccurrence() {
        val firstPass = at("2026-11-01T01:45:00-04:00")
        val item = task("2026-11-01", "01:45")
        assertTrue(Reminders.shouldFire(item, firstPass, newYork))
        assertNull(Reminders.nextOccurrence(Reminders.settle(item, firstPass, newYork)))
    }

    @Test
    fun planMovesToSecondPassOnceFirstHasPassed() {
        val item = task("2026-11-01", "01:45")
        val trigger = Reminders.plan(listOf(item), newYork, at("2026-11-01T01:50:00-04:00")).single().triggerMillis
        assertEquals(at("2026-11-01T01:45:00-05:00").toEpochMilli(), trigger)
    }

    @Test
    fun planOutsideOverlapIgnoresNow() {
        val item = task("2026-01-15")
        val trigger = Reminders.plan(listOf(item), paris, at("2026-02-01T00:00:00+01:00")).single().triggerMillis
        assertEquals(at("2026-01-15T09:00:00+01:00").toEpochMilli(), trigger)
    }

    @Test
    fun unmarkedRefusedOneOffStaysPlannedAndIsResetOnlyByFullSync() {
        val item = task("2026-01-15")
        val later = at("2026-01-16T00:00:00+01:00")
        val plan = Reminders.plan(listOf(item), paris, later)
        assertTrue(Reminders.shouldFire(item, later, paris))
        assertEquals(1, plan.size)
        assertTrue(Reminders.changes(plan, plan, false).set.isEmpty())
        assertEquals(plan, Reminders.changes(plan, plan, true).set)
    }

    @Test
    fun oneOffFiredUnderFutureClockFiresAgainAtRealTime() {
        val item = task("2026-11-07", "12:05")
        val fakeNow = at("2030-01-01T12:00:30+01:00")
        assertTrue(Reminders.shouldFire(item, fakeNow, paris))
        val fired = Reminders.settle(item, fakeNow, paris)
        assertEquals(ldt("2026-11-07T12:05"), fired.notified)
        val realNow = at("2026-11-07T11:55:00+01:00")
        val repaired = Reminders.repairClock(fired, realNow, paris)
        assertNull(repaired.notified)
        assertNull(repaired.settledAt)
        assertEquals(ldt("2026-11-07T12:05"), Reminders.nextOccurrence(repaired))
    }

    @Test
    fun oneOffFiredUnderFutureClockAndAlreadyPastStaysSilent() {
        val item = task("2026-11-07", "11:00")
        val fired = Reminders.settle(item, at("2030-01-01T12:00:30+01:00"), paris)
        val repaired = Reminders.repairClock(fired, at("2026-11-07T11:55:00+01:00"), paris)
        assertEquals(ldt("2026-11-07T11:00"), repaired.notified)
        assertNull(Reminders.nextOccurrence(repaired))
    }

    @Test
    fun settledAtIsRecordedOnlyWhenNotifiedChanges() {
        val now = at("2026-01-16T00:00:00+01:00")
        val once = Reminders.settle(task("2026-01-15"), now, paris)
        assertEquals(now.toEpochMilli(), once.settledAt)
        assertEquals(once, Reminders.settle(once, at("2026-01-17T00:00:00+01:00"), paris))
    }

    @Test
    fun smallClockCorrectionWithinToleranceKeepsFiredState() {
        val firedAt = at("2026-01-15T09:00:00+01:00")
        val fired = Reminders.settle(task("2026-01-15"), firedAt, paris)
        assertEquals(fired, Reminders.repairClock(fired, firedAt.minusSeconds(60), paris))
    }

    @Test
    fun largerClockCorrectionRearmsOneOffThatFiredEarly() {
        val firedAt = at("2026-01-15T09:00:00+01:00")
        val fired = Reminders.settle(task("2026-01-15"), firedAt, paris)
        val repaired = Reminders.repairClock(fired, firedAt.minusSeconds(600), paris)
        assertEquals(ldt("2026-01-15T09:00"), Reminders.nextOccurrence(repaired))
    }

    @Test
    fun activeReminderDetection() {
        assertTrue(Reminders.hasActiveReminder(listOf(task("2026-01-01"))))
        assertFalse(Reminders.hasActiveReminder(listOf(task("2026-01-01", remind = false))))
    }
}
