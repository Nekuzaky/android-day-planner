package dev.nekuz.dayboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

class ScheduleTextTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val saturdayBeforeSpring = at("2026-03-28T12:00:00+01:00")

    private fun at(text: String) = OffsetDateTime.parse(text).toInstant()
    private fun sec(text: String) = at(text).epochSecond
    private fun t(text: String) = LocalTime.parse(text)

    private fun slot(day: DayOfWeek, start: String, end: String, title: String = "Stream", id: Int = 1) =
        StreamSlot(id, title, day, t(start), t(end))

    private fun visibleActive(text: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < text.length) {
            if (text[index] == '\\') {
                index += 2
                continue
            }
            out.append(text[index])
            index += 1
        }
        return out.toString()
    }

    private fun assertInert(raw: String) {
        val escaped = ScheduleText.escapeTitle(raw)
        val active = visibleActive(escaped)
        "*_~`|<>[]()#:".forEach { assertFalse("$raw -> $escaped has $it", it in active) }
        assertFalse(raw, escaped.contains("@everyone", ignoreCase = true))
        assertFalse(raw, escaped.contains("@here", ignoreCase = true))
        assertFalse(raw, '\n' in escaped)
        assertFalse(raw, '\r' in escaped)
    }

    @Test
    fun plainTitleIsUnchanged() {
        assertEquals("Ranked climb 3", ScheduleText.escapeTitle("Ranked climb 3"))
    }

    @Test
    fun everyoneIsNeutralised() {
        assertEquals("@​everyone", ScheduleText.escapeTitle("@everyone"))
    }

    @Test
    fun hereIsNeutralised() {
        assertEquals("@​here", ScheduleText.escapeTitle("@here"))
    }

    @Test
    fun zeroWidthJoinerCannotRebuildMention() {
        listOf("@‍everyone", "@​everyone", "@﻿here", "‮@everyone", "@­everyone", "@\u0000here").forEach {
            assertInert(it)
        }
    }

    @Test
    fun caseVariantsAreNeutralised() {
        listOf("@EVERYONE", "@EveryOne", "@HERE", "@Here").forEach { assertInert(it) }
    }

    @Test
    fun repeatedAtSignsAreNeutralised() {
        assertInert("@@everyone")
        assertInert("a@here b@everyone")
    }

    @Test
    fun backslashCannotUnescape() {
        assertEquals("\\\\\\*", ScheduleText.escapeTitle("\\*"))
        assertInert("\\@everyone")
        assertInert("\\\\**bold**")
    }

    @Test
    fun mentionMarkupIsInert() {
        listOf("<@123456789012345678>", "<@!123>", "<@&123>", "<#123>", "<:emoji:123>", "<a:x:1>", "</cmd:1>").forEach {
            assertInert(it)
        }
    }

    @Test
    fun timestampMarkupIsInert() {
        assertInert("<t:0:F>")
    }

    @Test
    fun markdownIsInert() {
        listOf(
            "**bold**",
            "__under__",
            "*i*",
            "_i_",
            "~~gone~~",
            "||spoiler||",
            "`code`",
            "```fence```",
            "> quote",
            ">>> quote",
            "# heading",
            "## heading",
            "-# small",
            "[link](https://evil.example)",
            "https://evil.example",
            "discord.gg/abc",
        ).forEach { assertInert(it) }
    }

    @Test
    fun urlsCannotAutolink() {
        val escaped = ScheduleText.escapeTitle("https://evil.example/path")
        assertFalse("https://" in escaped)
        assertFalse("evil.example" in escaped)
    }

    @Test
    fun inviteCannotAutolink() {
        assertFalse("discord.gg/" in ScheduleText.escapeTitle("discord.gg/abc"))
    }

    @Test
    fun newlinesCannotCreateLines() {
        val text = ScheduleText.render(
            listOf(slot(DayOfWeek.MONDAY, "20:00", "22:00", "line\n# @everyone\r\n> x y z")),
            saturdayBeforeSpring,
            paris,
        )
        assertEquals(2, text.lines().size)
        assertFalse(text.contains("@everyone"))
    }

    @Test
    fun bidiOverrideIsRemoved() {
        assertEquals("abc", ScheduleText.escapeTitle("‮abc‬"))
    }

    @Test
    fun nullAndBlankBecomeUntitled() {
        assertEquals("Untitled", ScheduleText.escapeTitle(null))
        assertEquals("Untitled", ScheduleText.escapeTitle("   "))
        assertEquals("Untitled", ScheduleText.escapeTitle("​‍\u0000"))
    }

    @Test
    fun longTitleIsCapped() {
        assertTrue(ScheduleText.escapeTitle("x".repeat(100_000)).length <= Limits.MAX_SLOT_TITLE)
    }

    @Test
    fun longHostileTitleStaysInert() {
        assertInert("@everyone".repeat(5_000))
        assertInert("*".repeat(100_000))
    }

    @Test
    fun loneSurrogatesAreRemoved() {
        assertEquals("ab", ScheduleText.escapeTitle("a\uD83Db"))
    }

    @Test
    fun emojiIsKept() {
        assertEquals("🎮 live", ScheduleText.escapeTitle("🎮 live"))
    }

    @Test
    fun emptyScheduleIsEmptyText() {
        assertEquals("", ScheduleText.render(emptyList(), saturdayBeforeSpring, paris))
    }

    @Test
    fun exactRenderFormat() {
        val text = ScheduleText.render(
            listOf(slot(DayOfWeek.MONDAY, "20:00", "22:00", "Ranked")),
            at("2026-11-07T12:00:00+01:00"),
            paris,
        )
        val start = sec("2026-11-09T20:00:00+01:00")
        val end = sec("2026-11-09T22:00:00+01:00")
        assertEquals("**Stream schedule**\n<t:$start:F> to <t:$end:t> **Ranked**", text)
    }

    @Test
    fun hostileTitleInRenderLeavesOnlyOurMarkup() {
        val text = ScheduleText.render(
            listOf(slot(DayOfWeek.MONDAY, "20:00", "22:00", "<t:0:F> @everyone **x** <@1>")),
            saturdayBeforeSpring,
            paris,
        )
        val line = text.lines()[1]
        assertTrue(Regex("<t:[0-9]+:F> to <t:[0-9]+:t> \\*\\*.*\\*\\*").matches(line))
        assertEquals(2, Regex("(?<!\\\\)<t:").findAll(line).count())
        assertFalse(text.contains("@everyone"))
    }

    @Test
    fun todayLaterIsToday() {
        val item = ScheduleText.occurrence(slot(DayOfWeek.SATURDAY, "20:00", "22:00"), saturdayBeforeSpring, paris)
        assertEquals(sec("2026-03-28T20:00:00+01:00"), item.startEpoch)
    }

    @Test
    fun todayEarlierIsNextWeek() {
        val item = ScheduleText.occurrence(slot(DayOfWeek.SATURDAY, "10:00", "11:00"), saturdayBeforeSpring, paris)
        assertEquals(sec("2026-04-04T10:00:00+02:00"), item.startEpoch)
    }

    @Test
    fun exactlyNowIsNextWeek() {
        val item = ScheduleText.occurrence(slot(DayOfWeek.SATURDAY, "12:00", "13:00"), saturdayBeforeSpring, paris)
        assertEquals(sec("2026-04-04T12:00:00+02:00"), item.startEpoch)
    }

    @Test
    fun springForwardSlotAfterChange() {
        val item = ScheduleText.occurrence(slot(DayOfWeek.SUNDAY, "20:00", "22:00"), saturdayBeforeSpring, paris)
        assertEquals(sec("2026-03-29T20:00:00+02:00"), item.startEpoch)
        assertEquals(sec("2026-03-29T22:00:00+02:00"), item.endEpoch)
    }

    @Test
    fun startInSpringGapMovesAfterGap() {
        val item = ScheduleText.occurrence(slot(DayOfWeek.SUNDAY, "02:30", "04:00"), saturdayBeforeSpring, paris)
        assertEquals(sec("2026-03-29T03:30:00+02:00"), item.startEpoch)
        assertEquals(sec("2026-03-29T04:00:00+02:00"), item.endEpoch)
    }

    @Test
    fun endBeforeShiftedStartIsOmitted() {
        val item = ScheduleText.occurrence(slot(DayOfWeek.SUNDAY, "02:30", "03:00"), saturdayBeforeSpring, paris)
        assertEquals(sec("2026-03-29T03:30:00+02:00"), item.startEpoch)
        assertNull(item.endEpoch)
        val text = ScheduleText.render(listOf(slot(DayOfWeek.SUNDAY, "02:30", "03:00")), saturdayBeforeSpring, paris)
        assertFalse(" to " in text)
    }

    @Test
    fun fallBackOverlapUsesEarlierStart() {
        val item = ScheduleText.occurrence(
            slot(DayOfWeek.SUNDAY, "02:30", "03:30"),
            at("2026-10-24T12:00:00+02:00"),
            paris,
        )
        assertEquals(sec("2026-10-25T02:30:00+02:00"), item.startEpoch)
        assertEquals(sec("2026-10-25T03:30:00+01:00"), item.endEpoch)
    }

    @Test
    fun crossesMidnight() {
        val item = ScheduleText.occurrence(slot(DayOfWeek.SATURDAY, "23:00", "01:00"), saturdayBeforeSpring, paris)
        assertEquals(sec("2026-03-28T23:00:00+01:00"), item.startEpoch)
        assertEquals(sec("2026-03-29T01:00:00+01:00"), item.endEpoch)
    }

    @Test
    fun crossesMidnightIntoDstChange() {
        val item = ScheduleText.occurrence(slot(DayOfWeek.SATURDAY, "23:00", "04:00"), saturdayBeforeSpring, paris)
        assertEquals(sec("2026-03-29T04:00:00+02:00"), item.endEpoch)
    }

    @Test
    fun yearBoundary() {
        val item = ScheduleText.occurrence(slot(DayOfWeek.FRIDAY, "20:00", "22:00"), at("2026-12-31T12:00:00+01:00"), paris)
        assertEquals(sec("2027-01-01T20:00:00+01:00"), item.startEpoch)
    }

    @Test
    fun leapDay() {
        val item = ScheduleText.occurrence(slot(DayOfWeek.TUESDAY, "20:00", "22:00"), at("2028-02-28T12:00:00+01:00"), paris)
        assertEquals(sec("2028-02-29T20:00:00+01:00"), item.startEpoch)
    }

    @Test
    fun zoneDecidesInstantAndToday() {
        val now = at("2026-06-10T23:30:00-04:00")
        val slotItem = slot(DayOfWeek.THURSDAY, "20:00", "22:00")
        val inNewYork = ScheduleText.occurrence(slotItem, now, ZoneId.of("America/New_York"))
        val inTokyo = ScheduleText.occurrence(slotItem, now, ZoneId.of("Asia/Tokyo"))
        assertEquals(sec("2026-06-11T20:00:00-04:00"), inNewYork.startEpoch)
        assertEquals(sec("2026-06-11T20:00:00+09:00"), inTokyo.startEpoch)
    }

    @Test
    fun sortedByStartNotByDayNumber() {
        val now = at("2026-11-13T12:00:00+01:00")
        val text = ScheduleText.render(
            listOf(
                slot(DayOfWeek.MONDAY, "20:00", "22:00", "Mon", 1),
                slot(DayOfWeek.SATURDAY, "20:00", "22:00", "Sat", 2),
            ),
            now,
            paris,
        )
        assertTrue(text.indexOf("Sat") < text.indexOf("Mon"))
    }

    @Test
    fun digitsAreAsciiWhateverTheLocale() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-SA-u-nu-arab"))
            val text = ScheduleText.render(listOf(slot(DayOfWeek.MONDAY, "20:00", "22:00")), saturdayBeforeSpring, paris)
            assertTrue(Regex("<t:[0-9]+:F>").containsMatchIn(text))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun everyHostileSlotInOneRenderKeepsLineCount() {
        val titles = listOf("\n", "@everyone\n@here", "```", "<@1>", "‮", "||", "\\", "**")
        val slots = titles.mapIndexed { index, title -> slot(DayOfWeek.MONDAY, "20:00", "22:00", title, index + 1) }
        val text = ScheduleText.render(slots, saturdayBeforeSpring, paris)
        assertEquals(titles.size + 1, text.lines().size)
    }
}
