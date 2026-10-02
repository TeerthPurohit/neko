package dev.neko.app.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

private val heading = Regex("""^\s*#{1,6}\s+""")
private val bullet = Regex("""^(\s*)[-*+]\s+""")
private val rule = Regex("""^\s*([-*_]\s*){3,}$""")
private val inlineToken = Regex("""\*\*(.+?)\*\*|__(.+?)__|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])|`([^`]+)`|\[([^\]]+)]\([^)\s]+\)""")

/**
 * Model replies use a little Markdown (**bold**, *italic*, `code`, # headings, - lists, [links](...)). This renders those as styled text
 * instead of showing the symbols; anything else is left as written.
 */
fun markdown(text: String): AnnotatedString = buildAnnotatedString {
    text.trim().lines().forEachIndexed { index, raw ->
        if (index > 0) append('\n')
        val line = raw.trimEnd()
        when {
            rule.matches(line) -> Unit
            heading.containsMatchIn(line) -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(line.replace(heading, "")) }
            else -> inline(bullet.find(line)?.let { it.groupValues[1] + "•  " + line.substring(it.range.last + 1) } ?: line)
        }
    }
}

private fun AnnotatedString.Builder.inline(text: String) {
    var at = 0
    for (match in inlineToken.findAll(text)) {
        append(text.substring(at, match.range.first))
        val g = match.groupValues
        when {
            g[1].isNotEmpty() || g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(g[1].ifEmpty { g[2] }) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[3]) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(g[4]) }
            else -> append(g[5])
        }
        at = match.range.last + 1
    }
    append(text.substring(at))
}
