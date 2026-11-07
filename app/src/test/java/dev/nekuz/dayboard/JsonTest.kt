package dev.nekuz.dayboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {
    private fun rejected(text: String) {
        assertThrows(text, JsonException::class.java) { Json.parse(text) }
    }

    @Test
    fun parsesScalars() {
        assertEquals(JsonValue.Null, Json.parse("null"))
        assertEquals(JsonValue.Bool(true), Json.parse("true"))
        assertEquals(JsonValue.Bool(false), Json.parse("false"))
        assertEquals(JsonValue.Number(42), Json.parse("42"))
        assertEquals(JsonValue.Number(-7), Json.parse("-7"))
        assertEquals(JsonValue.Number(0), Json.parse("0"))
        assertEquals(JsonValue.Text("hi"), Json.parse("\"hi\""))
    }

    @Test
    fun parsesContainersAndWhitespace() {
        val value = Json.parse(" { \"a\" : [ 1 , 2 ] ,\n\t\"b\" : { } , \"c\" : [ ] } ")
        val fields = (value as JsonValue.Fields).values
        assertEquals(JsonValue.Items(listOf(JsonValue.Number(1), JsonValue.Number(2))), fields["a"])
        assertEquals(JsonValue.Fields(emptyMap()), fields["b"])
        assertEquals(JsonValue.Items(emptyList()), fields["c"])
    }

    @Test
    fun parsesEscapes() {
        assertEquals(JsonValue.Text("\"\\/\b\u000C\n\r\t\u00e9"), Json.parse("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u00E9\""))
    }

    @Test
    fun rejectsBadStructure() {
        listOf(
            "",
            " ",
            "{",
            "}",
            "[",
            "]",
            "[1,",
            "[1,]",
            "[,1]",
            "[1 2]",
            "{\"a\"}",
            "{\"a\":}",
            "{\"a\":1,}",
            "{a:1}",
            "{'a':1}",
            "{1:2}",
            "\"a",
            "1 2",
            "null null",
            "{} {}",
            "nul",
            "tru",
            "True",
            "NaN",
            "Infinity",
            "undefined",
            "// c\n1",
            "/* c */ 1",
            "[1]]",
        ).forEach(::rejected)
    }

    @Test
    fun rejectsBadNumbers() {
        listOf("01", "-", "--1", "+1", "1.0", "1e5", "1E5", ".5", "0x10", "1234567890123456789012", "9999999999999999999").forEach(::rejected)
    }

    @Test
    fun rejectsBadStrings() {
        listOf("\"\n\"", "\"\t\"", "\"\u0000\"", "\"\\x\"", "\"\\u12\"", "\"\\u12G4\"", "\"\\", "\"\\u").forEach(::rejected)
    }

    @Test
    fun rejectsDuplicateKeys() {
        rejected("{\"a\":1,\"a\":2}")
        rejected("{\"a\":1,\"\\u0061\":2}")
    }

    @Test
    fun rejectsExcessiveDepth() {
        rejected("[".repeat(Json.MAX_DEPTH + 1) + "]".repeat(Json.MAX_DEPTH + 1))
        rejected("{\"a\":".repeat(Json.MAX_DEPTH + 1) + "1" + "}".repeat(Json.MAX_DEPTH + 1))
    }

    @Test
    fun acceptsMaximumDepth() {
        assertNotNull(Json.parse("[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH)))
    }

    @Test
    fun hugeNestingDoesNotOverflowStack() {
        rejected("[".repeat(5_000_000))
    }

    @Test
    fun rejectsTooManyValues() {
        rejected("[" + List(Json.MAX_NODES) { "[]" }.joinToString(",") + "]")
    }

    @Test
    fun rejectsByteOrderMark() {
        rejected("\uFEFF{}")
    }

    @Test
    fun roundTripsHostileText() {
        val text = "a\"b\\c\n\r\t\u0000\u001F\u2028\u2029\uD83D\uDE00\uD83D<script>"
        val written = Json.write(JsonValue.Text(text))
        assertEquals(JsonValue.Text(text), Json.parse(written))
    }

    @Test
    fun writtenTextHasNoRawControls() {
        val written = Json.write(JsonValue.Text("\u0000\u0001\n\u2028"))
        assertTrue(written.none { it.code < 0x20 || it == '\u2028' })
    }

    @Test
    fun roundTripsStructure() {
        val value = JsonValue.Fields(
            linkedMapOf(
                "a" to JsonValue.Items(listOf(JsonValue.Number(-1), JsonValue.Null, JsonValue.Bool(true))),
                "b" to JsonValue.Fields(emptyMap()),
                "c" to JsonValue.Text(""),
            ),
        )
        assertEquals(value, Json.parse(Json.write(value)))
    }

    @Test
    fun largestAcceptedNumberRoundTrips() {
        assertEquals(JsonValue.Number(999_999_999_999_999_999), Json.parse("999999999999999999"))
    }
}
