package com.heddrich.companion.llm

/**
 * Deterministischer Mini-Markdown-zu-HTML-Renderer (Golden-Test abgedeckt).
 * Unterstuetzt genau die Bausteine, die der Summary-Prompt erzeugt:
 * h2/h3, Absaetze, Bullet-/Nummernlisten, Checkboxen, Fettdruck, Inline-Code.
 * Bewusst KEIN vollwertiger Parser (YAGNI).
 */
object MdRenderer {

    fun render(md: String): String {
        val out = StringBuilder("<div>")
        var inList: String? = null // "ul" | "ol"

        fun closeList() {
            if (inList != null) {
                out.append("</").append(inList).append(">")
                inList = null
            }
        }

        for (rawLine in md.lines()) {
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> closeList()
                trimmed.startsWith("### ") -> {
                    closeList(); out.append("<h3>").append(inline(trimmed.substring(4))).append("</h3>")
                }
                trimmed.startsWith("## ") -> {
                    closeList(); out.append("<h2>").append(inline(trimmed.substring(3))).append("</h2>")
                }
                trimmed.startsWith("# ") -> {
                    closeList(); out.append("<h2>").append(inline(trimmed.substring(2))).append("</h2>")
                }
                Regex("^\\s*[-*]\\s+\\[[ xX]]\\s+").containsMatchIn(line) -> {
                    if (inList != "ul") { closeList(); out.append("<ul>"); inList = "ul" }
                    val text = trimmed.replace(Regex("^[-*]\\s+\\[[ xX]]\\s+"), "")
                    val checked = Regex("^[-*]\\s+\\[[xX]]").containsMatchIn(trimmed)
                    out.append("<li>").append(if (checked) "\u2611 " else "\u2610 ")
                        .append(inline(text)).append("</li>")
                }
                Regex("^\\s*[-*]\\s+").containsMatchIn(line) -> {
                    if (inList != "ul") { closeList(); out.append("<ul>"); inList = "ul" }
                    out.append("<li>").append(inline(trimmed.replace(Regex("^[-*]\\s+"), ""))).append("</li>")
                }
                Regex("^\\s*\\d+\\.\\s+").containsMatchIn(line) -> {
                    if (inList != "ol") { closeList(); out.append("<ol>"); inList = "ol" }
                    out.append("<li>").append(inline(trimmed.replace(Regex("^\\d+\\.\\s+"), ""))).append("</li>")
                }
                else -> {
                    closeList(); out.append("<p>").append(inline(trimmed)).append("</p>")
                }
            }
        }
        closeList()
        return out.append("</div>").toString()
    }

    /** Inline: Fettdruck **x** und Code `x`; HTML wird escaped. */
    private fun inline(text: String): String {
        val esc = escapeHtml(text)
        return esc
            .replace(Regex("\\*\\*(.+?)\\*\\*")) { "<strong>${it.groupValues[1]}</strong>" }
            .replace(Regex("`([^`]+)`")) { "<code>${it.groupValues[1]}</code>" }
    }

    internal fun escapeHtml(text: String): String {
        val sb = StringBuilder(text.length + 32)
        for (c in text) when (c) {
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '&' -> sb.append("&amp;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&#39;")
            else -> sb.append(c)
        }
        return sb.toString()
    }
}
