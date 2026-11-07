package dev.nekuz.dayboard

import java.time.Instant
import java.time.ZoneId

data class SlotOccurrence(val slot: StreamSlot, val startEpoch: Long, val endEpoch: Long?)

object ScheduleText {
    const val HEADING = "**Stream schedule**"
    private const val ZERO_WIDTH_SPACE = '\u200B'
    private const val MARKUP = "\\*_~`|<>[]()#-+:/."
    private const val UNTITLED = "Untitled"
    private const val DAYS_IN_WEEK = 7L

    fun escapeTitle(raw: CharSequence?): String {
        val cleaned = Titles.clean(raw, Limits.MAX_SLOT_TITLE).ifEmpty { UNTITLED }
        val out = StringBuilder(cleaned.length + 16)
        for (char in cleaned) {
            when {
                char == '@' -> out.append('@').append(ZERO_WIDTH_SPACE)
                char in MARKUP -> out.append('\\').append(char)
                else -> out.append(char)
            }
        }
        return out.toString()
    }

    fun occurrence(slot: StreamSlot, now: Instant, zone: ZoneId): SlotOccurrence {
        val today = now.atZone(zone).toLocalDate()
        val offset = (slot.day.value - today.dayOfWeek.value + DAYS_IN_WEEK) % DAYS_IN_WEEK
        var date = today.plusDays(offset)
        if (!Zones.instant(date, slot.start, zone).isAfter(now)) date = date.plusDays(DAYS_IN_WEEK)
        val start = Zones.instant(date, slot.start, zone).epochSecond
        val endDate = if (slot.end.isAfter(slot.start)) date else date.plusDays(1)
        val end = Zones.instant(endDate, slot.end, zone).epochSecond
        return SlotOccurrence(slot, start, if (end > start) end else null)
    }

    fun occurrences(slots: List<StreamSlot>, now: Instant, zone: ZoneId): List<SlotOccurrence> =
        slots.map { occurrence(it, now, zone) }
            .sortedWith(compareBy({ it.startEpoch }, { it.slot.id }))

    fun render(slots: List<StreamSlot>, now: Instant, zone: ZoneId): String {
        if (slots.isEmpty()) return ""
        val out = StringBuilder(HEADING)
        for (item in occurrences(slots, now, zone)) {
            out.append('\n').append("<t:").append(item.startEpoch).append(":F>")
            if (item.endEpoch != null) out.append(" to <t:").append(item.endEpoch).append(":t>")
            out.append(" **").append(escapeTitle(item.slot.title)).append("**")
        }
        return out.toString()
    }
}
