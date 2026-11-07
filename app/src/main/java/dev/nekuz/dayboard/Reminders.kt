package dev.nekuz.dayboard

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

data class PlannedAlarm(val taskId: Int, val triggerMillis: Long)

data class AlarmChanges(val cancel: Set<Int>, val set: List<PlannedAlarm>)

object Reminders {
    const val MAX_PLANNED = 400
    const val FIRE_SLACK_MILLIS = 60_000L
    const val MAX_ZONE_SPREAD_HOURS = 27L
    const val CLOCK_BACK_TOLERANCE_MILLIS = 2 * FIRE_SLACK_MILLIS
    private const val MAX_DAYS_BETWEEN_OCCURRENCES = 64

    fun nextOccurrence(task: Task): LocalDateTime? {
        if (!task.remind || task.doneOn != null) return null
        val time = task.time ?: return null
        val due = LocalDateTime.of(task.date, time)
        val notified = task.notified ?: return due
        if (due.isAfter(notified)) return due
        if (task.repeat == Repeat.None) return null
        val notifiedDay = notified.toLocalDate()
        if (Recurrence.occursOn(task.repeat, notifiedDay)) {
            val sameDay = LocalDateTime.of(notifiedDay, time)
            if (sameDay.isAfter(notified)) return sameDay
        }
        val next = Recurrence.nextAfter(task.repeat, notifiedDay) ?: return null
        return LocalDateTime.of(next, time)
    }

    fun latestDue(task: Task, now: Instant, zone: ZoneId): LocalDateTime? =
        latestDue(task, now, zone) { Zones.instant(it, zone) }

    private fun latestDue(
        task: Task,
        now: Instant,
        zone: ZoneId,
        resolve: (LocalDateTime) -> Instant,
    ): LocalDateTime? {
        val next = nextOccurrence(task) ?: return null
        if (resolve(next).isAfter(now)) return null
        if (task.repeat == Repeat.None) return next
        val time = task.time ?: return null
        var day = now.atZone(zone).toLocalDate().plusDays(1)
        repeat(MAX_DAYS_BETWEEN_OCCURRENCES) {
            if (!day.isBefore(next.toLocalDate()) && Recurrence.occursOn(task.repeat, day)) {
                val candidate = LocalDateTime.of(day, time)
                if (!resolve(candidate).isAfter(now)) return candidate
            }
            day = day.minusDays(1)
        }
        return next
    }

    fun settle(task: Task, now: Instant, zone: ZoneId): Task =
        marked(task, latestDue(task, now, zone), now)

    fun settleEdited(task: Task, now: Instant, zone: ZoneId): Task =
        marked(task, latestDue(task, now, zone) { Zones.latestInstant(it, zone) }, now)

    private fun marked(task: Task, due: LocalDateTime?, now: Instant): Task =
        if (due == null || due == task.notified) task else task.copy(notified = due, settledAt = now.toEpochMilli())

    fun repairClock(task: Task, now: Instant, zone: ZoneId): Task {
        val notified = task.notified
        val settledAt = task.settledAt
        val limit = now.atZone(zone).toLocalDateTime().plusHours(MAX_ZONE_SPREAD_HOURS)
        val aheadOfClock = notified != null && notified.isAfter(limit)
        val settledInFuture = settledAt != null && settledAt > now.toEpochMilli() + CLOCK_BACK_TOLERANCE_MILLIS
        if (!aheadOfClock && !settledInFuture) return task
        return settleEdited(task.copy(notified = null, settledAt = null), now, zone)
    }

    fun shouldFire(task: Task, now: Instant, zone: ZoneId): Boolean {
        val next = nextOccurrence(task) ?: return false
        return Zones.instant(next, zone).toEpochMilli() <= now.toEpochMilli() + FIRE_SLACK_MILLIS
    }

    fun plan(tasks: List<Task>, zone: ZoneId, now: Instant, limit: Int = MAX_PLANNED): List<PlannedAlarm> =
        tasks.mapNotNull { task ->
            nextOccurrence(task)?.let {
                PlannedAlarm(task.id, Zones.instantAfterPassedOverlap(it, zone, now).toEpochMilli())
            }
        }.sortedWith(compareBy({ it.triggerMillis }, { it.taskId })).take(limit)

    fun changes(
        previous: List<PlannedAlarm>,
        planned: List<PlannedAlarm>,
        force: Boolean,
    ): AlarmChanges {
        val wanted = planned.mapTo(HashSet()) { it.taskId }
        val cancel = previous.mapTo(HashSet()) { it.taskId }.apply { removeAll(wanted) }
        if (force) return AlarmChanges(cancel, planned)
        val known = previous.associate { it.taskId to it.triggerMillis }
        return AlarmChanges(cancel, planned.filter { known[it.taskId] != it.triggerMillis })
    }

    fun staleNotifications(before: List<Task>, after: List<Task>): Set<Int> {
        val current = after.associateBy { it.id }
        val stale = HashSet<Int>()
        for (task in before) {
            val now = current[task.id]
            if (now == task) continue
            if (now == null || now.doneOn != null || now.date != task.date || now.time != task.time || !now.remind) {
                stale.add(task.id)
            }
        }
        return stale
    }

    fun hasActiveReminder(tasks: List<Task>): Boolean = tasks.any { nextOccurrence(it) != null }
}
