package com.pako.eminus4.data.remote.miuv

/**
 * Lectura mínima de HTML de páginas ASP.NET de MiUV sin WebView: elementos por id (con su texto completo,
 * como `textContent` en el navegador, respetando anidados) y campos ocultos de formulario.
 */
object HtmlElements {

    data class Element(val id: String, val tag: String, val cssClass: String, val text: String)

    private val OPENING_TAG = Regex("""<([a-zA-Z][a-zA-Z0-9]*)(\s[^>]*?)?\sid="([^"]+)"([^>]*)>""")
    private val CLASS_ATTR = Regex("""\bclass="([^"]*)"""")
    private val VOID_TAGS = setOf("input", "img", "br", "hr", "meta", "link")
    private val HIDDEN_INPUT = Regex("""<input[^>]*type="hidden"[^>]*>""", RegexOption.IGNORE_CASE)

    /** Cada elemento con `id="…"`, en orden de aparición. */
    fun withIds(html: String): List<Element> = OPENING_TAG.findAll(html).map { m ->
        val tag = m.groupValues[1].lowercase()
        val attributes = m.groupValues[2] + " " + m.groupValues[4]
        val start = m.range.last + 1
        val text = if (tag in VOID_TAGS || m.value.endsWith("/>")) "" else plainText(html.substring(start, closingIndex(html, tag, start)))
        Element(m.groupValues[3], tag, CLASS_ATTR.find(attributes)?.groupValues?.get(1).orEmpty(), text)
    }.toList()

    /** Campos `<input type="hidden">` (nombre → valor): __VIEWSTATE, __EVENTVALIDATION… */
    fun hiddenFields(html: String): List<Pair<String, String>> = HIDDEN_INPUT.findAll(html).mapNotNull { m ->
        val name = Regex("""name="([^"]*)"""").find(m.value)?.groupValues?.get(1) ?: return@mapNotNull null
        name to unescape(Regex("""value="([^"]*)"""").find(m.value)?.groupValues?.get(1).orEmpty())
    }.toList()

    fun unescape(value: String) = value.replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    private fun plainText(fragment: String) = unescape(fragment.replace(Regex("<[^>]+>"), " "))
        .replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()

    /** Posición del `</tag>` que cierra el elemento abierto antes de [from] (cuenta anidados del mismo tipo). */
    private fun closingIndex(html: String, tag: String, from: Int): Int {
        val tagPattern = Regex("""<(/?)$tag\b[^>]*?(/?)>""", RegexOption.IGNORE_CASE)
        var depth = 1
        var position = from
        while (true) {
            val m = tagPattern.find(html, position) ?: return html.length
            if (m.groupValues[1] == "/") {
                if (--depth == 0) return m.range.first
            } else if (m.groupValues[2] != "/") {
                depth++
            }
            position = m.range.last + 1
        }
    }
}
