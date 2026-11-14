package io.github.wangnan05563.airmirror.util

/**
 * 极简 JSON 解析（RFC 8259 的常用子集）。
 *
 * 为什么自己写：项目无 AndroidX、无第三方网络库，而 JVM 单测跑在纯 JDK 上，
 * `org.json` 只在 Android 运行时存在——用它会让「在线更新」的解析逻辑无法被单测锁住。
 * 只实现更新检查真正需要的对象/数组/字符串/数字/布尔/null，不做流式解析与容错修复：
 * 输入来自 GitHub Releases API，格式非法就该当场失败，而不是猜。
 */
sealed interface JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val raw: String) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue

    fun field(name: String): JsonValue? = (this as? Obj)?.fields?.get(name)
    fun text(name: String): String? = (field(name) as? Str)?.value
    fun items(): List<JsonValue> = (this as? Arr)?.items.orEmpty()
}

class JsonException(message: String) : IllegalArgumentException(message)

object Json {
    /** GitHub Release 这类响应最深不到十层；64 层足够宽松，又能挡住让栈溢出的畸形输入 */
    private const val MAX_DEPTH = 64

    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        val value = parser.value()
        parser.skipWhitespace()
        if (!parser.done()) throw JsonException("trailing content at ${parser.position}")
        return value
    }

    private class Parser(private val text: String) {
        var position = 0
            private set

        private var depth = 0

        fun done(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        fun value(): JsonValue {
            skipWhitespace()
            if (done()) throw JsonException("unexpected end of input")
            return when (val ch = text[position]) {
                '{' -> descend { obj() }
                '[' -> descend { arr() }
                '"' -> JsonValue.Str(string())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> if (ch == '-' || ch.isDigit()) number() else throw JsonException("unexpected '$ch' at $position")
            }
        }

        /**
         * 递归下降必须封顶：一兆字节的 `[[[[[` 是几十万层，会在栈上直接 StackOverflowError。
         * 那是 Error，外层的 `catch (IllegalArgumentException)` 抓不住，整个进程会被带走。
         */
        private fun descend(parse: () -> JsonValue): JsonValue {
            if (depth >= MAX_DEPTH) throw JsonException("nesting too deep: $depth")
            depth++
            try {
                return parse()
            } finally {
                depth--
            }
        }

        private fun literal(word: String, result: JsonValue): JsonValue {
            if (!text.startsWith(word, position)) throw JsonException("bad literal at $position")
            position += word.length
            return result
        }

        private fun obj(): JsonValue.Obj {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                position++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                val key = string()
                skipWhitespace()
                expect(':')
                fields[key] = value()
                skipWhitespace()
                when (val next = peek()) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return JsonValue.Obj(fields)
                    }
                    else -> throw JsonException("expected ',' or '}' at $position, got '$next'")
                }
            }
        }

        private fun arr(): JsonValue.Arr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                position++
                return JsonValue.Arr(items)
            }
            while (true) {
                items += value()
                skipWhitespace()
                when (val next = peek()) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return JsonValue.Arr(items)
                    }
                    else -> throw JsonException("expected ',' or ']' at $position, got '$next'")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (done()) throw JsonException("unterminated string")
                when (val ch = text[position++]) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (done()) throw JsonException("unterminated escape")
                        when (val escape = text[position++]) {
                            '"', '\\', '/' -> builder.append(escape)
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) throw JsonException("bad unicode escape")
                                val hex = text.substring(position, position + 4)
                                position += 4
                                val code = hex.toIntOrNull(16) ?: throw JsonException("bad unicode escape: $hex")
                                builder.append(code.toChar())
                            }
                            else -> throw JsonException("bad escape '\\$escape'")
                        }
                    }
                    else -> builder.append(ch)
                }
            }
        }

        private fun number(): JsonValue.Num {
            val start = position
            if (peek() == '-') position++
            skipDigits()
            if (!done() && text[position] == '.') {
                position++
                skipDigits()
            }
            skipExponent()
            if (position == start) throw JsonException("bad number at $start")
            val raw = text.substring(start, position)
            raw.toDoubleOrNull() ?: throw JsonException("bad number: $raw")
            return JsonValue.Num(raw)
        }

        private fun skipDigits() {
            while (!done() && text[position].isDigit()) position++
        }

        /** 指数部分：e/E 后可选正负号，再跟一段数字；合法性最终由 toDoubleOrNull 兜底。 */
        private fun skipExponent() {
            if (done()) return
            if (text[position] != 'e' && text[position] != 'E') return
            position++
            if (!done() && (text[position] == '+' || text[position] == '-')) position++
            skipDigits()
        }

        private fun peek(): Char {
            if (done()) throw JsonException("unexpected end of input")
            return text[position]
        }

        private fun expect(ch: Char) {
            if (peek() != ch) throw JsonException("expected '$ch' at $position, got '${text[position]}'")
            position++
        }
    }
}
