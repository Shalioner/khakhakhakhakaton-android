package ru.neurosteno.core

/** Экспорт строго по контракту, без сторонних зависимостей. */
object JsonExporter {

    fun toJson(t: Transcript, pretty: Boolean = true): String {
        val nl = if (pretty) "\n" else ""
        val i1 = if (pretty) "  " else ""
        val i2 = if (pretty) "    " else ""
        val sp = if (pretty) " " else ""
        val sb = StringBuilder()
        sb.append("{").append(nl).append(i1).append("\"transcript\":").append(sp).append("[")
        t.utterances.forEachIndexed { idx, u ->
            if (idx > 0) sb.append(",")
            sb.append(nl).append(i2).append("{")
            sb.append("\"speaker_id\":").append(sp).append(quote(u.speakerId)).append(",").append(sp)
            sb.append("\"start_ms\":").append(sp).append(u.startMs).append(",").append(sp)
            sb.append("\"end_ms\":").append(sp).append(u.endMs).append(",").append(sp)
            sb.append("\"text\":").append(sp).append(quote(u.text))
            sb.append("}")
        }
        if (t.utterances.isNotEmpty()) sb.append(nl).append(i1)
        sb.append("]").append(nl).append("}").append(nl)
        return sb.toString()
    }

    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
