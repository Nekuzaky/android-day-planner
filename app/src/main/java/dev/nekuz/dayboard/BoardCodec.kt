package dev.nekuz.dayboard

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

sealed class Decoded {
    data class Ok(val board: Board) : Decoded()

    data class Failed(val reason: String) : Decoded()
}

object BoardCodec {
    const val FORMAT = "dayboard"
    const val VERSION = 1L

    private val ROOT_KEYS = setOf("format", "version", "nextId", "tasks", "habits", "slots")
    private val TASK_KEYS = setOf(
        "id", "title", "date", "time", "remind", "repeat", "doneOn", "lastDoneOn", "previousDate", "notified",
        "settledAt",
    )
    private val REPEAT_KEYS = setOf("kind", "days", "day")
    private val HABIT_KEYS = setOf("id", "name", "checkIns")
    private val SLOT_KEYS = setOf("id", "title", "day", "start", "end")
    private val DATE_PATTERN = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private val TIME_PATTERN = Regex("[0-9]{2}:[0-9]{2}")

    fun encode(board: Board): String = Json.write(
        fields(
            "format" to JsonValue.Text(FORMAT),
            "version" to JsonValue.Number(VERSION),
            "nextId" to number(board.nextId),
            "tasks" to JsonValue.Items(board.tasks.map(::encodeTask)),
            "habits" to JsonValue.Items(board.habits.map(::encodeHabit)),
            "slots" to JsonValue.Items(board.slots.map(::encodeSlot)),
        ),
    )

    fun decode(text: String): Decoded {
        val root = try {
            Json.parse(text)
        } catch (e: JsonException) {
            return Decoded.Failed("not valid JSON")
        }
        return try {
            Decoded.Ok(decodeBoard(root))
        } catch (e: BadField) {
            Decoded.Failed(e.field)
        } catch (e: DateTimeException) {
            Decoded.Failed("date or time out of range")
        } catch (e: ArithmeticException) {
            Decoded.Failed("number out of range")
        }
    }

    private class BadField(val field: String) : Exception(field)

    private fun bad(field: String): Nothing = throw BadField("invalid $field")

    private fun fields(vararg pairs: Pair<String, JsonValue>) = JsonValue.Fields(linkedMapOf(*pairs))

    private fun number(value: Int) = JsonValue.Number(value.toLong())

    private fun textOrNull(value: Any?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Text(value.toString())

    private fun encodeTask(task: Task): JsonValue = fields(
        "id" to number(task.id),
        "title" to JsonValue.Text(task.title),
        "date" to JsonValue.Text(task.date.toString()),
        "time" to textOrNull(task.time?.let(::formatTime)),
        "remind" to JsonValue.Bool(task.remind),
        "repeat" to encodeRepeat(task.repeat),
        "doneOn" to textOrNull(task.doneOn),
        "lastDoneOn" to textOrNull(task.lastDoneOn),
        "previousDate" to textOrNull(task.previousDate),
        "notified" to textOrNull(task.notified?.let { "${it.toLocalDate()}T${formatTime(it.toLocalTime())}" }),
        "settledAt" to (task.settledAt?.let { JsonValue.Number(it) } ?: JsonValue.Null),
    )

    private fun formatTime(time: LocalTime): String {
        val hour = time.hour
        val minute = time.minute
        val out = StringBuilder(5)
        if (hour < 10) out.append('0')
        out.append(hour).append(':')
        if (minute < 10) out.append('0')
        out.append(minute)
        return out.toString()
    }

    private fun encodeRepeat(repeat: Repeat): JsonValue = when (repeat) {
        Repeat.None -> fields("kind" to JsonValue.Text("none"))
        Repeat.Daily -> fields("kind" to JsonValue.Text("daily"))
        Repeat.Weekdays -> fields("kind" to JsonValue.Text("weekdays"))
        is Repeat.Weekly -> fields(
            "kind" to JsonValue.Text("weekly"),
            "days" to JsonValue.Items(repeat.days.map { it.value }.sorted().map(::number)),
        )
        is Repeat.Monthly -> fields("kind" to JsonValue.Text("monthly"), "day" to number(repeat.day))
    }

    private fun encodeHabit(habit: Habit): JsonValue = fields(
        "id" to number(habit.id),
        "name" to JsonValue.Text(habit.name),
        "checkIns" to JsonValue.Items(habit.checkIns.map { JsonValue.Text(it.toString()) }),
    )

    private fun encodeSlot(slot: StreamSlot): JsonValue = fields(
        "id" to number(slot.id),
        "title" to JsonValue.Text(slot.title),
        "day" to number(slot.day.value),
        "start" to JsonValue.Text(formatTime(slot.start)),
        "end" to JsonValue.Text(formatTime(slot.end)),
    )

    private fun decodeBoard(root: JsonValue): Board {
        val map = objectOf(root, ROOT_KEYS, "backup")
        if ((map["format"] as? JsonValue.Text)?.value != FORMAT) bad("format")
        if ((map["version"] as? JsonValue.Number)?.value != VERSION) bad("version")
        val nextId = intOf(map["nextId"], 1, Limits.MAX_ID + 1, "nextId")
        val tasks = itemsOf(map["tasks"], Limits.MAX_TASKS, "tasks").map(::decodeTask)
        val habits = itemsOf(map["habits"], Limits.MAX_HABITS, "habits").map(::decodeHabit)
        val slots = itemsOf(map["slots"], Limits.MAX_SLOTS, "slots").map(::decodeSlot)
        val ids = HashSet<Int>()
        val all = tasks.map { it.id } + habits.map { it.id } + slots.map { it.id }
        for (id in all) {
            if (!ids.add(id)) bad("id: duplicate")
            if (id >= nextId) bad("nextId")
        }
        return Board(nextId, tasks, habits, slots)
    }

    private fun decodeTask(value: JsonValue): Task {
        val map = objectOf(value, TASK_KEYS, "task")
        val id = idOf(map["id"], "task id")
        val title = titleOf(map["title"], Limits.MAX_TITLE, "task title")
        val date = dateOf(map["date"], "task date")
        val time = timeOrNull(map["time"], "task time")
        val remind = boolOf(map["remind"], "task remind")
        val repeat = decodeRepeat(map["repeat"])
        val doneOn = dateOrNull(map["doneOn"], "task doneOn")
        val lastDoneOn = dateOrNull(map["lastDoneOn"], "task lastDoneOn")
        val previousDate = dateOrNull(map["previousDate"], "task previousDate")
        val notified = dateTimeOrNull(map["notified"], "task notified")
        val settledAt = millisOrNull(map["settledAt"], "task settledAt")
        if (remind && time == null) bad("task remind without time")
        if (previousDate != null && repeat == Repeat.None) bad("task previousDate")
        return Task(id, title, date, time, remind, repeat, doneOn, lastDoneOn, previousDate, notified, settledAt)
    }

    private fun decodeRepeat(value: JsonValue?): Repeat {
        val map = objectOf(value, REPEAT_KEYS, "task repeat")
        val kind = (map["kind"] as? JsonValue.Text)?.value ?: bad("task repeat")
        val hasDays = map.containsKey("days")
        val hasDay = map.containsKey("day")
        return when (kind) {
            "none", "daily", "weekdays" -> {
                if (hasDays || hasDay) bad("task repeat")
                when (kind) {
                    "none" -> Repeat.None
                    "daily" -> Repeat.Daily
                    else -> Repeat.Weekdays
                }
            }
            "weekly" -> {
                if (hasDay) bad("task repeat")
                val items = itemsOf(map["days"], 7, "task repeat days")
                if (items.isEmpty()) bad("task repeat days")
                val days = items.mapTo(HashSet()) { dayOf(it, "task repeat days") }
                if (days.size != items.size) bad("task repeat days")
                Repeat.Weekly(days)
            }
            "monthly" -> {
                if (hasDays) bad("task repeat")
                Repeat.Monthly(intOf(map["day"], 1, 31, "task repeat day"))
            }
            else -> bad("task repeat")
        }
    }

    private fun decodeHabit(value: JsonValue): Habit {
        val map = objectOf(value, HABIT_KEYS, "habit")
        val id = idOf(map["id"], "habit id")
        val name = titleOf(map["name"], Limits.MAX_HABIT_NAME, "habit name")
        val checkIns = itemsOf(map["checkIns"], Limits.MAX_CHECK_INS, "habit checkIns").map { dateOf(it, "habit checkIns") }
        for (index in 1 until checkIns.size) {
            if (!checkIns[index].isAfter(checkIns[index - 1])) bad("habit checkIns order")
        }
        return Habit(id, name, checkIns)
    }

    private fun decodeSlot(value: JsonValue): StreamSlot {
        val map = objectOf(value, SLOT_KEYS, "slot")
        val start = timeOf(map["start"], "slot start")
        val end = timeOf(map["end"], "slot end")
        if (start == end) bad("slot end")
        return StreamSlot(
            idOf(map["id"], "slot id"),
            titleOf(map["title"], Limits.MAX_SLOT_TITLE, "slot title"),
            dayOf(map["day"], "slot day"),
            start,
            end,
        )
    }

    private fun objectOf(value: JsonValue?, keys: Set<String>, field: String): Map<String, JsonValue> {
        val map = (value as? JsonValue.Fields)?.values ?: bad(field)
        for (key in map.keys) if (key !in keys) bad("$field: unknown field")
        if (keys !== REPEAT_KEYS && map.size != keys.size) bad("$field: missing field")
        return map
    }

    private fun itemsOf(value: JsonValue?, max: Int, field: String): List<JsonValue> {
        val items = (value as? JsonValue.Items)?.values ?: bad(field)
        if (items.size > max) bad("$field: too many")
        return items
    }

    private fun intOf(value: JsonValue?, min: Int, max: Int, field: String): Int {
        val number = (value as? JsonValue.Number)?.value ?: bad(field)
        if (number < min || number > max) bad(field)
        return number.toInt()
    }

    private fun idOf(value: JsonValue?, field: String) = intOf(value, 1, Limits.MAX_ID, field)

    private fun millisOrNull(value: JsonValue?, field: String): Long? {
        if (value == JsonValue.Null) return null
        val number = (value as? JsonValue.Number)?.value ?: bad(field)
        if (number < 0 || number > Limits.MAX_EPOCH_MILLIS) bad(field)
        return number
    }

    private fun boolOf(value: JsonValue?, field: String): Boolean = (value as? JsonValue.Bool)?.value ?: bad(field)

    private fun titleOf(value: JsonValue?, max: Int, field: String): String {
        val text = (value as? JsonValue.Text)?.value ?: bad(field)
        if (text.length > max) bad(field)
        val cleaned = Titles.clean(text, max)
        if (cleaned.isEmpty()) bad(field)
        return cleaned
    }

    private fun dayOf(value: JsonValue?, field: String): DayOfWeek = DayOfWeek.of(intOf(value, 1, 7, field))

    private fun dateOf(value: JsonValue?, field: String): LocalDate {
        val text = (value as? JsonValue.Text)?.value ?: bad(field)
        return parseDate(text, field)
    }

    private fun parseDate(text: String, field: String): LocalDate {
        if (!DATE_PATTERN.matches(text)) bad(field)
        val date = try {
            LocalDate.parse(text)
        } catch (e: DateTimeException) {
            bad(field)
        }
        if (!Zones.isValidYear(date)) bad(field)
        return date
    }

    private fun dateOrNull(value: JsonValue?, field: String): LocalDate? =
        if (value == JsonValue.Null) null else dateOf(value, field)

    private fun timeOf(value: JsonValue?, field: String): LocalTime {
        val text = (value as? JsonValue.Text)?.value ?: bad(field)
        return parseTime(text, field)
    }

    private fun parseTime(text: String, field: String): LocalTime {
        if (!TIME_PATTERN.matches(text)) bad(field)
        return try {
            LocalTime.parse(text)
        } catch (e: DateTimeException) {
            bad(field)
        }
    }

    private fun timeOrNull(value: JsonValue?, field: String): LocalTime? =
        if (value == JsonValue.Null) null else timeOf(value, field)

    private fun dateTimeOrNull(value: JsonValue?, field: String): LocalDateTime? {
        if (value == JsonValue.Null) return null
        val text = (value as? JsonValue.Text)?.value ?: bad(field)
        val split = text.indexOf('T')
        if (split < 0) bad(field)
        return LocalDateTime.of(
            parseDate(text.substring(0, split), field),
            parseTime(text.substring(split + 1), field),
        )
    }
}

object AlarmLedger {
    private val ROOT_KEYS = setOf("exact", "notify", "alarms")
    private val ITEM_KEYS = setOf("id", "at")
    private const val MAX_TRIGGER = 253_402_300_799_000L

    data class Entry(val exact: Boolean, val notify: Boolean, val alarms: List<PlannedAlarm>)

    fun needsFullSync(previous: Entry?, exact: Boolean, notify: Boolean): Boolean =
        previous == null || previous.exact != exact || previous.notify != notify

    fun encode(entry: Entry): String = Json.write(
        JsonValue.Fields(
            linkedMapOf(
                "exact" to JsonValue.Bool(entry.exact),
                "notify" to JsonValue.Bool(entry.notify),
                "alarms" to JsonValue.Items(
                    entry.alarms.map {
                        JsonValue.Fields(
                            linkedMapOf(
                                "id" to JsonValue.Number(it.taskId.toLong()),
                                "at" to JsonValue.Number(it.triggerMillis),
                            ),
                        )
                    },
                ),
            ),
        ),
    )

    fun decode(text: String): Entry? {
        val root = try {
            Json.parse(text)
        } catch (e: JsonException) {
            return null
        }
        val map = (root as? JsonValue.Fields)?.values ?: return null
        if (map.keys != ROOT_KEYS) return null
        val exact = (map["exact"] as? JsonValue.Bool)?.value ?: return null
        val notify = (map["notify"] as? JsonValue.Bool)?.value ?: return null
        val items = (map["alarms"] as? JsonValue.Items)?.values ?: return null
        if (items.size > Reminders.MAX_PLANNED) return null
        val alarms = ArrayList<PlannedAlarm>(items.size)
        for (item in items) {
            val fields = (item as? JsonValue.Fields)?.values ?: return null
            if (fields.keys != ITEM_KEYS) return null
            val id = (fields["id"] as? JsonValue.Number)?.value ?: return null
            val at = (fields["at"] as? JsonValue.Number)?.value ?: return null
            if (id < 1 || id > Limits.MAX_ID || at < 0 || at > MAX_TRIGGER) return null
            alarms.add(PlannedAlarm(id.toInt(), at))
        }
        return Entry(exact, notify, alarms)
    }
}
