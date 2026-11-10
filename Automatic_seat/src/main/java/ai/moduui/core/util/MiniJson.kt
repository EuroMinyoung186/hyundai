package ai.moduui.core.util

/**
 * core 모듈은 안드로이드 의존성이 없어 org.json을 못 씀. Gleo 파라미터·콜백·스키마 정도만 다루는 작은 JSON 도구.
 * 지원: object, array, string, number(Double/Long), boolean, null.
 */
object MiniJson {

    fun parse(text: String): Any? = Parser(text).run {
        val v = readValue()
        skipWs()
        require(pos == text.length) { "trailing characters at $pos" }
        v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String?): Map<String, Any?> {
        if (text.isNullOrBlank()) return emptyMap()
        return parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("not a JSON object")
    }

    fun stringify(value: Any?, indent: String? = null): String = StringBuilder().also { write(it, value, indent, "") }.toString()

    private fun write(sb: StringBuilder, v: Any?, indent: String?, cur: String) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v)
            is Double -> sb.append(if (v % 1.0 == 0.0 && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString())
            is Float -> write(sb, v.toDouble(), indent, cur)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                val next = if (indent != null) cur + indent else cur
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    if (indent != null) sb.append('\n').append(next)
                    quote(sb, k.toString())
                    sb.append(if (indent != null) ": " else ":")
                    write(sb, value, indent, next)
                }
                if (indent != null) sb.append('\n').append(cur)
                sb.append('}')
            }
            is Iterable<*> -> {
                val list = v.toList()
                if (list.isEmpty()) { sb.append("[]"); return }
                val next = if (indent != null) cur + indent else cur
                sb.append('[')
                list.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    if (indent != null) sb.append('\n').append(next)
                    write(sb, item, indent, next)
                }
                if (indent != null) sb.append('\n').append(cur)
                sb.append(']')
            }
            is Array<*> -> write(sb, v.toList(), indent, cur)
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            skipWs()
            require(pos < s.length) { "unexpected end" }
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw IllegalArgumentException("unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, pos)) { "bad literal at $pos" }
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val map = linkedMapOf<String, Any?>()
            pos++
            skipWs()
            if (s[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                require(s[pos] == '"') { "expected key at $pos" }
                val key = readString()
                skipWs()
                require(s[pos] == ':') { "expected ':' at $pos" }
                pos++
                map[key] = readValue()
                skipWs()
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val list = mutableListOf<Any?>()
            pos++
            skipWs()
            if (s[pos] == ']') { pos++; return list }
            while (true) {
                list += readValue()
                skipWs()
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            val sb = StringBuilder()
            pos++
            while (true) {
                require(pos < s.length) { "unterminated string" }
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[pos++]
                        when (e) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                            else -> throw IllegalArgumentException("bad escape at $pos")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Number {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val text = s.substring(start, pos)
            return if (text.any { it in ".eE" }) text.toDouble() else text.toLongOrNull() ?: text.toDouble()
        }
    }
}

/** 파라미터 읽기 도우미: Gleo가 숫자를 문자열로 줄 수도 있어 둘 다 받아 줌 */
fun Map<String, Any?>.intOrNull(key: String): Int? = when (val v = this[key]) {
    is Number -> v.toInt()
    is String -> v.trim().toDoubleOrNull()?.toInt()
    else -> null
}

fun Map<String, Any?>.stringOrNull(key: String): String? = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

fun Map<String, Any?>.boolOrNull(key: String): Boolean? = when (val v = this[key]) {
    is Boolean -> v
    is String -> v.trim().lowercase().let { if (it == "true") true else if (it == "false") false else null }
    else -> null
}
