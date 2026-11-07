package dev.nekuz.dayboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

class BoardCodecTest {
    private val fullBoard = Board(
        nextId = 20,
        tasks = listOf(
            Task(1, "One", d("2026-01-31")),
            Task(
                id = 2,
                title = "Two \"quoted\" \\ é 😀",
                date = d("2026-03-29"),
                time = LocalTime.of(2, 30),
                remind = true,
                repeat = Repeat.Daily,
                lastDoneOn = d("2026-03-28"),
                previousDate = d("2026-03-28"),
                notified = LocalDateTime.of(2026, 3, 28, 2, 30),
                settledAt = 1_774_659_000_000,
            ),
            Task(3, "Three", d("2028-02-29"), LocalTime.of(0, 0), repeat = Repeat.Weekdays),
            Task(4, "Four", d("2026-01-05"), repeat = Repeat.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY))),
            Task(5, "Five", d("2026-01-31"), LocalTime.of(23, 59), repeat = Repeat.Monthly(31), doneOn = d("2026-02-01")),
        ),
        habits = listOf(Habit(6, "Read", listOf(d("2028-02-28"), d("2028-02-29"))), Habit(7, "Empty")),
        slots = listOf(StreamSlot(8, "Ranked", DayOfWeek.FRIDAY, LocalTime.of(20, 0), LocalTime.of(1, 0))),
    )

    private val validTask = """{"id":1,"title":"x","date":"2026-01-01","time":null,"remind":false,"repeat":{"kind":"none"},"doneOn":null,"lastDoneOn":null,"previousDate":null,"notified":null,"settledAt":null}"""

    private fun root(tasks: String = "[]", habits: String = "[]", slots: String = "[]", nextId: String = "100") =
        """{"format":"dayboard","version":1,"nextId":$nextId,"tasks":$tasks,"habits":$habits,"slots":$slots}"""

    private fun ok(text: String): Board {
        val decoded = BoardCodec.decode(text)
        assertTrue("$text -> $decoded", decoded is Decoded.Ok)
        return (decoded as Decoded.Ok).board
    }

    private fun refused(text: String) {
        assertTrue(text, BoardCodec.decode(text) is Decoded.Failed)
    }

    private fun refusedTask(from: String, to: String) {
        assertTrue(from, from in validTask)
        refused(root(tasks = "[${validTask.replace(from, to)}]"))
    }

    @Test
    fun roundTrip() {
        assertEquals(fullBoard, ok(BoardCodec.encode(fullBoard)))
    }

    @Test
    fun emptyBoardRoundTrip() {
        assertEquals(Board(), ok(BoardCodec.encode(Board())))
    }

    @Test
    fun encodingIsStable() {
        val once = BoardCodec.encode(fullBoard)
        assertEquals(once, BoardCodec.encode(ok(once)))
    }

    @Test
    fun acceptsValidTask() {
        assertEquals(1, ok(root(tasks = "[$validTask]")).tasks.size)
    }

    @Test
    fun refusesGarbage() {
        listOf("", "garbage", "null", "[]", "{}", "1", "\"x\"", "{\"format\":\"dayboard\"}").forEach(::refused)
    }

    @Test
    fun refusesWrongFormatAndVersion() {
        refused(root().replace("\"dayboard\"", "\"other\""))
        refused(root().replace("\"version\":1", "\"version\":2"))
        refused(root().replace("\"version\":1", "\"version\":\"1\""))
        refused(root().replace("\"version\":1", "\"version\":0"))
    }

    @Test
    fun refusesUnknownAndMissingRootFields() {
        refused(root().replace("\"version\":1", "\"version\":1,\"extra\":1"))
        refused("""{"format":"dayboard","version":1,"nextId":1,"tasks":[],"habits":[]}""")
    }

    @Test
    fun refusesBadNextId() {
        listOf("0", "-1", "1000000002", "99999999999", "\"1\"", "null", "true").forEach { refused(root(nextId = it)) }
    }

    @Test
    fun refusesNextIdNotAboveIds() {
        refused(root(tasks = "[$validTask]", nextId = "1"))
    }

    @Test
    fun refusesDuplicateIdsInAndAcrossLists() {
        refused(root(tasks = "[$validTask,$validTask]"))
        val habit = """{"id":1,"name":"h","checkIns":[]}"""
        refused(root(tasks = "[$validTask]", habits = "[$habit]"))
    }

    @Test
    fun refusesBadTaskIds() {
        listOf("0", "-5", "1000000001", "\"1\"", "null").forEach { refusedTask("\"id\":1", "\"id\":$it") }
    }

    @Test
    fun refusesBadTitles() {
        refusedTask("\"title\":\"x\"", "\"title\":\"\"")
        refusedTask("\"title\":\"x\"", "\"title\":null")
        refusedTask("\"title\":\"x\"", "\"title\":1")
        refusedTask("\"title\":\"x\"", "\"title\":\"   \"")
        refusedTask("\"title\":\"x\"", "\"title\":\"\\u202E\\u200B\"")
        refusedTask("\"title\":\"x\"", "\"title\":\"${"y".repeat(Limits.MAX_TITLE + 1)}\"")
    }

    @Test
    fun acceptsTitleAtLimit() {
        val text = root(tasks = "[${validTask.replace("\"title\":\"x\"", "\"title\":\"${"y".repeat(Limits.MAX_TITLE)}\"")}]")
        assertEquals(Limits.MAX_TITLE, ok(text).tasks[0].title.length)
    }

    @Test
    fun cleansHostileTitles() {
        val text = root(tasks = "[${validTask.replace("\"title\":\"x\"", "\"title\":\"a\\u202Eb\\nc\\u0000\"")}]")
        assertEquals("abc", ok(text).tasks[0].title)
    }

    @Test
    fun refusesBadDates() {
        listOf(
            "2026-02-29",
            "2026-13-01",
            "2026-00-10",
            "2026-1-1",
            "20260101",
            "+2026-01-01",
            "-2026-01-01",
            "1969-12-31",
            "3000-01-01",
            "2026-01-01T00:00",
            "٢٠٢٦-٠١-٠١",
            "",
        ).forEach { refusedTask("\"date\":\"2026-01-01\"", "\"date\":\"$it\"") }
        refusedTask("\"date\":\"2026-01-01\"", "\"date\":null")
        refusedTask("\"date\":\"2026-01-01\"", "\"date\":20260101")
    }

    @Test
    fun acceptsLeapDay() {
        assertEquals(
            d("2028-02-29"),
            ok(root(tasks = "[${validTask.replace("2026-01-01", "2028-02-29")}]")).tasks[0].date,
        )
    }

    @Test
    fun refusesBadTimes() {
        listOf("24:00", "12:60", "1:00", "12:0", "12:00:00", "aa:bb", "", "-1:00").forEach {
            refusedTask("\"time\":null", "\"time\":\"$it\"")
        }
        refusedTask("\"time\":null", "\"time\":900")
    }

    @Test
    fun refusesRemindWithoutTime() {
        refusedTask("\"remind\":false", "\"remind\":true")
    }

    @Test
    fun refusesBadRemindType() {
        refusedTask("\"remind\":false", "\"remind\":\"false\"")
        refusedTask("\"remind\":false", "\"remind\":0")
        refusedTask("\"remind\":false", "\"remind\":null")
    }

    @Test
    fun refusesBadRepeat() {
        listOf(
            "null",
            "1",
            "{}",
            """{"kind":"never"}""",
            """{"kind":1}""",
            """{"kind":"weekly"}""",
            """{"kind":"weekly","days":[]}""",
            """{"kind":"weekly","days":[0]}""",
            """{"kind":"weekly","days":[8]}""",
            """{"kind":"weekly","days":[1,1]}""",
            """{"kind":"weekly","days":[1,2,3,4,5,6,7,1]}""",
            """{"kind":"weekly","days":["MONDAY"]}""",
            """{"kind":"weekly","days":[1],"day":1}""",
            """{"kind":"monthly"}""",
            """{"kind":"monthly","day":0}""",
            """{"kind":"monthly","day":32}""",
            """{"kind":"monthly","day":"1"}""",
            """{"kind":"monthly","day":1,"days":[1]}""",
            """{"kind":"daily","day":1}""",
            """{"kind":"none","days":[1]}""",
            """{"kind":"daily","x":1}""",
        ).forEach { refusedTask("""{"kind":"none"}""", it) }
    }

    @Test
    fun refusesPreviousDateOnOneOff() {
        refusedTask("\"previousDate\":null", "\"previousDate\":\"2026-01-01\"")
    }

    @Test
    fun refusesBadOptionalDates() {
        refusedTask("\"doneOn\":null", "\"doneOn\":\"2026-02-30\"")
        refusedTask("\"lastDoneOn\":null", "\"lastDoneOn\":5")
        refusedTask("\"notified\":null", "\"notified\":\"2026-01-01\"")
        refusedTask("\"notified\":null", "\"notified\":\"2026-01-01T25:00\"")
        refusedTask("\"notified\":null", "\"notified\":\"2026-02-30T10:00\"")
        refusedTask("\"notified\":null", "\"notified\":\"T\"")
    }

    @Test
    fun refusesBadSettledAt() {
        listOf("-1", "\"1\"", "true", "32503680000001").forEach { refusedTask("\"settledAt\":null", "\"settledAt\":$it") }
    }

    @Test
    fun refusesMissingAndUnknownTaskFields() {
        refused(root(tasks = "[${validTask.replace("\"settledAt\":null", "\"extra\":null")}]"))
        refused(root(tasks = "[${validTask.replace(",\"settledAt\":null", "")}]"))
        refused(root(tasks = "[1]"))
        refused(root(tasks = "{}"))
        refused(root(tasks = "null"))
    }

    @Test
    fun refusesTooManyTasks() {
        val items = (1..Limits.MAX_TASKS + 1).joinToString(",") { validTask.replace("\"id\":1", "\"id\":$it") }
        refused(root(tasks = "[$items]", nextId = "5000"))
    }

    @Test
    fun acceptsMaximumTasks() {
        val items = (1..Limits.MAX_TASKS).joinToString(",") { validTask.replace("\"id\":1", "\"id\":$it") }
        assertEquals(Limits.MAX_TASKS, ok(root(tasks = "[$items]", nextId = "5000")).tasks.size)
    }

    @Test
    fun refusesBadHabits() {
        listOf(
            """{"id":1,"name":"","checkIns":[]}""",
            """{"id":1,"name":"h"}""",
            """{"id":1,"name":"h","checkIns":["2026-01-02","2026-01-01"]}""",
            """{"id":1,"name":"h","checkIns":["2026-01-01","2026-01-01"]}""",
            """{"id":1,"name":"h","checkIns":["2026-02-30"]}""",
            """{"id":1,"name":"h","checkIns":[1]}""",
            """{"id":1,"name":"${"h".repeat(Limits.MAX_HABIT_NAME + 1)}","checkIns":[]}""",
            """{"id":0,"name":"h","checkIns":[]}""",
        ).forEach { refused(root(habits = "[$it]")) }
    }

    @Test
    fun refusesTooManyHabits() {
        val items = (1..Limits.MAX_HABITS + 1).joinToString(",") { """{"id":$it,"name":"h","checkIns":[]}""" }
        refused(root(habits = "[$items]"))
    }

    @Test
    fun refusesBadSlots() {
        listOf(
            """{"id":1,"title":"s","day":0,"start":"20:00","end":"22:00"}""",
            """{"id":1,"title":"s","day":8,"start":"20:00","end":"22:00"}""",
            """{"id":1,"title":"s","day":"MONDAY","start":"20:00","end":"22:00"}""",
            """{"id":1,"title":"s","day":1,"start":"20:00","end":"20:00"}""",
            """{"id":1,"title":"s","day":1,"start":"25:00","end":"22:00"}""",
            """{"id":1,"title":"s","day":1,"start":"20:00","end":null}""",
            """{"id":1,"title":"","day":1,"start":"20:00","end":"22:00"}""",
            """{"id":1,"title":"${"s".repeat(Limits.MAX_SLOT_TITLE + 1)}","day":1,"start":"20:00","end":"22:00"}""",
            """{"id":1,"title":"s","day":1,"start":"20:00"}""",
        ).forEach { refused(root(slots = "[$it]")) }
    }

    @Test
    fun refusesTooManySlots() {
        val items = (1..Limits.MAX_SLOTS + 1).joinToString(",") {
            """{"id":$it,"title":"s","day":1,"start":"20:00","end":"22:00"}"""
        }
        refused(root(slots = "[$items]"))
    }

    @Test
    fun deepJsonBombIsRefused() {
        refused(root(tasks = "[".repeat(100_000)))
    }

    @Test
    fun failureReasonNeverEchoesInput() {
        val decoded = BoardCodec.decode(root(tasks = "[${validTask.replace("2026-01-01", "<script>EVIL")}]"))
        val reason = (decoded as Decoded.Failed).reason
        assertTrue(reason, "EVIL" !in reason)
    }

    @Test
    fun maximumDataFitsTheFileCap() {
        val title = "中".repeat(Limits.MAX_TITLE)
        val days = (0L until Limits.MAX_CHECK_INS).map { d("1970-01-01").plusDays(it) }
        var id = 0
        val board = Board(
            nextId = Limits.MAX_TASKS + Limits.MAX_HABITS + Limits.MAX_SLOTS + 1,
            tasks = (1..Limits.MAX_TASKS).map { Task(++id, title, d("2026-01-01"), LocalTime.NOON, true, Repeat.Weekly(DayOfWeek.entries.toSet()), notified = LocalDateTime.of(2026, 1, 1, 12, 0)) },
            habits = (1..Limits.MAX_HABITS).map { Habit(++id, "中".repeat(Limits.MAX_HABIT_NAME), days) },
            slots = (1..Limits.MAX_SLOTS).map { StreamSlot(++id, "中".repeat(Limits.MAX_SLOT_TITLE), DayOfWeek.MONDAY, LocalTime.of(1, 0), LocalTime.of(2, 0)) },
        )
        val text = BoardCodec.encode(board)
        assertTrue(text.toByteArray().size <= Limits.MAX_FILE_BYTES)
        assertEquals(board, ok(text))
    }

    @Test
    fun ledgerRoundTrip() {
        val entry = AlarmLedger.Entry(true, false, listOf(PlannedAlarm(1, 1000), PlannedAlarm(2, 2000)))
        assertEquals(entry, AlarmLedger.decode(AlarmLedger.encode(entry)))
    }

    @Test
    fun ledgerRefusesMalformed() {
        listOf(
            "",
            "x",
            "[]",
            "{}",
            """{"exact":1,"notify":true,"alarms":[]}""",
            """{"exact":true,"notify":true}""",
            """{"exact":true,"notify":true,"alarms":[1]}""",
            """{"exact":true,"notify":true,"alarms":[{"id":0,"at":1}]}""",
            """{"exact":true,"notify":true,"alarms":[{"id":1,"at":-1}]}""",
            """{"exact":true,"notify":true,"alarms":[{"id":1}]}""",
            """{"exact":true,"notify":true,"alarms":[{"id":1,"at":999999999999999999}]}""",
            """{"exact":true,"notify":true,"alarms":[{"id":1,"at":1,"x":1}]}""",
        ).forEach { assertNull(it, AlarmLedger.decode(it)) }
    }

    @Test
    fun ledgerRefusesMissingOrBadNotify() {
        assertNull(AlarmLedger.decode("""{"exact":true,"alarms":[]}"""))
        assertNull(AlarmLedger.decode("""{"exact":true,"notify":1,"alarms":[]}"""))
    }

    @Test
    fun fullSyncWhenExactOrNotificationStateChanges() {
        val entry = AlarmLedger.Entry(true, false, emptyList())
        assertTrue(AlarmLedger.needsFullSync(null, true, false))
        assertTrue(AlarmLedger.needsFullSync(entry, false, false))
        assertTrue(AlarmLedger.needsFullSync(entry, true, true))
        assertTrue(!AlarmLedger.needsFullSync(entry, true, false))
    }

    @Test
    fun ledgerAcceptsEmpty() {
        assertNotNull(AlarmLedger.decode("""{"exact":false,"notify":true,"alarms":[]}"""))
    }
}
