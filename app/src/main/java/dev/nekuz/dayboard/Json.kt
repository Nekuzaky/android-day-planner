package dev.nekuz.dayboard

sealed class JsonValue {
    data object Null : JsonValue()

    data class Bool(val value: Boolean) : JsonValue()

    data class Number(val value: Long) : JsonValue()

    data class Text(val value: String) : JsonValue()

    data class Items(val values: List<JsonValue>) : JsonValue()

    data class Fields(val values: Map<String, JsonValue>) : JsonValue()
}

class JsonException(message: String) : Exception(message)

object Json {
    const val MAX_DEPTH = 16
    const val MAX_NODES = 600_000
    private const val MAX_NUMBER_DIGITS = 18
    private const val HEX = "0123456789abcdef"

    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        return parser.document()
    }

    fun write(value: JsonValue): String {
        val out = StringBuilder()
        append(out, value)
        return out.toString()
    }

    private fun append(out: StringBuilder, value: JsonValue) {
        when (value) {
            JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            is JsonValue.Number -> out.append(value.value)
            is JsonValue.Text -> appendText(out, value.value)
            is JsonValue.Items -> {
                out.append('[')
                value.values.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    append(out, item)
                }
                out.append(']')
            }
            is JsonValue.Fields -> {
                out.append('{')
                var first = true
                for ((key, item) in value.values) {
                    if (!first) out.append(',')
                    first = false
                    appendText(out, key)
                    out.append(':')
                    append(out, item)
                }
                out.append('}')
            }
        }
    }

    private fun appendText(out: StringBuilder, text: String) {
        out.append('"')
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char < ' ' || char == ' ' || char == ' ' -> appendUnicode(out, char)
                Character.isHighSurrogate(char) && index + 1 < text.length && Character.isLowSurrogate(text[index + 1]) -> {
                    out.append(char).append(text[index + 1])
                    index += 1
                }
                Character.isSurrogate(char) -> appendUnicode(out, char)
                else -> out.append(char)
            }
            index += 1
        }
        out.append('"')
    }

    private fun appendUnicode(out: StringBuilder, char: Char) {
        val code = char.code
        out.append("\\u")
        out.append(HEX[(code shr 12) and 0xF])
        out.append(HEX[(code shr 8) and 0xF])
        out.append(HEX[(code shr 4) and 0xF])
        out.append(HEX[code and 0xF])
    }

    private class Parser(private val text: String) {
        private var index = 0
        private var nodes = 0

        fun document(): JsonValue {
            skipSpace()
            val value = value(1)
            skipSpace()
            if (index != text.length) fail("trailing data")
            return value
        }

        private fun value(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("too deep")
            nodes += 1
            if (nodes > MAX_NODES) fail("too many values")
            if (index >= text.length) fail("unexpected end")
            return when (text[index]) {
                '{' -> fields(depth)
                '[' -> items(depth)
                '"' -> JsonValue.Text(string())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> number()
            }
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, index)) fail("bad literal")
            index += word.length
            return value
        }

        private fun fields(depth: Int): JsonValue {
            index += 1
            val values = LinkedHashMap<String, JsonValue>()
            skipSpace()
            if (peek() == '}') {
                index += 1
                return JsonValue.Fields(values)
            }
            while (true) {
                skipSpace()
                if (peek() != '"') fail("expected key")
                val key = string()
                skipSpace()
                if (peek() != ':') fail("expected colon")
                index += 1
                skipSpace()
                if (values.containsKey(key)) fail("duplicate key")
                values[key] = value(depth + 1)
                skipSpace()
                when (peek()) {
                    ',' -> index += 1
                    '}' -> {
                        index += 1
                        return JsonValue.Fields(values)
                    }
                    else -> fail("expected comma")
                }
            }
        }

        private fun items(depth: Int): JsonValue {
            index += 1
            val values = ArrayList<JsonValue>()
            skipSpace()
            if (peek() == ']') {
                index += 1
                return JsonValue.Items(values)
            }
            while (true) {
                skipSpace()
                values.add(value(depth + 1))
                skipSpace()
                when (peek()) {
                    ',' -> index += 1
                    ']' -> {
                        index += 1
                        return JsonValue.Items(values)
                    }
                    else -> fail("expected comma")
                }
            }
        }

        private fun string(): String {
            index += 1
            val out = StringBuilder()
            while (true) {
                if (index >= text.length) fail("unterminated string")
                val char = text[index]
                index += 1
                when {
                    char == '"' -> return out.toString()
                    char == '\\' -> escape(out)
                    char < ' ' -> fail("raw control character")
                    else -> out.append(char)
                }
            }
        }

        private fun escape(out: StringBuilder) {
            if (index >= text.length) fail("unterminated escape")
            val char = text[index]
            index += 1
            when (char) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> out.append(unicode())
                else -> fail("bad escape")
            }
        }

        private fun unicode(): Char {
            if (index + 4 > text.length) fail("bad unicode escape")
            var code = 0
            repeat(4) {
                val digit = hexDigit(text[index])
                if (digit < 0) fail("bad unicode escape")
                code = code * 16 + digit
                index += 1
            }
            return code.toChar()
        }

        private fun hexDigit(char: Char): Int = when (char) {
            in '0'..'9' -> char - '0'
            in 'a'..'f' -> char - 'a' + 10
            in 'A'..'F' -> char - 'A' + 10
            else -> -1
        }

        private fun number(): JsonValue {
            val start = index
            if (peek() == '-') index += 1
            val digits = index
            while (index < text.length && text[index] in '0'..'9') index += 1
            val count = index - digits
            if (count == 0 || count > MAX_NUMBER_DIGITS) fail("bad number")
            if (text[digits] == '0' && count > 1) fail("bad number")
            if (index < text.length && (text[index] == '.' || text[index] == 'e' || text[index] == 'E')) {
                fail("only integers are allowed")
            }
            return JsonValue.Number(text.substring(start, index).toLong())
        }

        private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

        private fun skipSpace() {
            while (index < text.length) {
                when (text[index]) {
                    ' ', '\t', '\n', '\r' -> index += 1
                    else -> return
                }
            }
        }

        private fun fail(message: String): Nothing = throw JsonException(message)
    }
}
