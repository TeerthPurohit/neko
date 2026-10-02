package dev.neko.core

import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.zip.ZipInputStream
import kotlin.math.abs

/**
 * Turns spreadsheet bank statements into CSV text that [StatementParser] can read. Handles .xlsx (a zip of XML), and the HTML tables or
 * "SpreadsheetML 2003" XML that many banks save with an .xls extension. Classic binary .xls is read by the app (see StatementReader).
 */
object Spreadsheets {
    private const val MAX_UNZIPPED = 40L * 1024 * 1024
    private const val MAX_ENTRIES = 300
    private val dot = setOf(RegexOption.DOT_MATCHES_ALL)

    fun isZip(bytes: ByteArray): Boolean = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() && bytes[2].toInt() == 3 && bytes[3].toInt() == 4
    fun isOle(bytes: ByteArray): Boolean = bytes.size > 8 && bytes.take(8) == listOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1).map { it.toByte() }

    /** One CSV string per worksheet, in workbook order, or null when [bytes] is not a readable .xlsx. */
    fun xlsxSheetsAsCsv(bytes: ByteArray): List<String>? {
        if (!isZip(bytes)) return null
        val files = HashMap<String, String>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var total = 0L; var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++count > MAX_ENTRIES) return null
                    val wanted = entry.name == "xl/sharedStrings.xml" || Regex("xl/worksheets/sheet\\d+\\.xml").matches(entry.name)
                    val buffer = java.io.ByteArrayOutputStream(); val chunk = ByteArray(32 * 1024)
                    while (true) {
                        val n = zip.read(chunk); if (n < 0) break
                        total += n; if (total > MAX_UNZIPPED) return null // a zip bomb or an unreasonably large workbook
                        if (wanted) buffer.write(chunk, 0, n)
                    }
                    if (wanted) files[entry.name] = buffer.toString(Charsets.UTF_8.name())
                }
            }
        } catch (_: java.io.IOException) { return null } catch (_: IllegalArgumentException) { return null }
        val sheetNames = files.keys.filter { it.startsWith("xl/worksheets/") }.sortedBy { Regex("\\d+").find(it)!!.value.toInt() }
        if (sheetNames.isEmpty()) return null
        val shared = files["xl/sharedStrings.xml"]?.let(::sharedStrings).orEmpty()
        return sheetNames.map { sheetCsv(files.getValue(it), shared) }
    }

    private fun sharedStrings(xml: String): List<String> =
        Regex("<si\\b[^>]*?(?:/>|>(.*?)</si>)", dot).findAll(xml).map { m -> textRuns(m.groupValues[1].replace(Regex("<rPh\\b.*?</rPh>", dot), "")) }.toList()

    private fun textRuns(xml: String): String = Regex("<t\\b[^>]*?(?:/>|>(.*?)</t>)", dot).findAll(xml).joinToString("") { decode(it.groupValues[1]) }

    private fun columnIndex(reference: String): Int = reference.takeWhile { it.isLetter() }.fold(0) { n, c -> n * 26 + (c.uppercaseChar() - 'A' + 1) } - 1

    private fun sheetCsv(xml: String, shared: List<String>): String {
        val lines = ArrayList<String>()
        for (row in Regex("<row\\b[^>]*?(?:/>|>(.*?)</row>)", dot).findAll(xml)) {
            val cells = ArrayList<String>()
            for (cell in Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", dot).findAll(row.groupValues[1])) {
                val attributes = cell.groupValues[1]; val body = cell.groupValues[2]
                val column = Regex("\\br=\"([A-Za-z]+)\\d+\"").find(attributes)?.let { columnIndex(it.groupValues[1]) } ?: cells.size
                if (column < 0 || column > 200) continue
                while (cells.size < column) cells += ""
                val type = Regex("\\bt=\"([^\"]*)\"").find(attributes)?.groupValues?.get(1)
                val raw = Regex("<v>(.*?)</v>", dot).find(body)?.groupValues?.get(1)
                val value = when (type) {
                    "s" -> raw?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> textRuns(body)
                    "str" -> decode(raw.orEmpty())
                    "b" -> if (raw?.trim() == "1") "TRUE" else "FALSE"
                    "e" -> ""
                    else -> raw?.trim()?.toDoubleOrNull()?.let(::cleanNumber) ?: decode(raw.orEmpty())
                }
                if (column < cells.size) cells[column] = value else cells += value
            }
            if (cells.any { it.isNotBlank() }) lines += csvLine(cells)
        }
        return lines.joinToString("\n")
    }

    /** Whole numbers stay whole (dates are serial numbers); fractions are rounded half up to paise from their shortest decimal form, so 98765.50000000001 reads as 98765.50 and 1.005 as 1.01. */
    fun cleanNumber(value: Double): String =
        if (value.isNaN() || value.isInfinite()) "" else if (value == Math.rint(value) && abs(value) < 1e15) value.toLong().toString() else BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString()

    fun csvLine(cells: List<String>): String = cells.joinToString(",") { raw ->
        val cell = raw.replace('\r', ' ').replace('\n', ' ')
        if (cell.any { it == ',' || it == '"' }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell
    }

    // ---------- HTML tables and SpreadsheetML 2003 ----------
    /** One CSV string per table in an HTML page or SpreadsheetML 2003 workbook; empty when the text holds neither. */
    fun htmlTablesAsCsv(text: String): List<String> {
        val ignoreCase = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        if (Regex("<Row\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) && Regex("<Data\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return Regex("<Table\\b.*?</Table>", ignoreCase).findAll(text).map { table ->
                Regex("<Row\\b[^>]*?(?:/>|>(.*?)</Row>)", ignoreCase).findAll(table.value).map { row ->
                    val cells = ArrayList<String>()
                    for (cell in Regex("<Cell\\b([^>]*?)(?:/>|>(.*?)</Cell>)", ignoreCase).findAll(row.groupValues[1])) {
                        Regex("Index=\"(\\d+)\"").find(cell.groupValues[1])?.groupValues?.get(1)?.toIntOrNull()?.let { while (cells.size < it - 1) cells += "" }
                        cells += Regex("<Data\\b[^>]*>(.*?)</Data>", ignoreCase).find(cell.groupValues[2])?.groupValues?.get(1)?.let(::plain).orEmpty()
                    }
                    csvLine(cells)
                }.filter { it.isNotBlank() && it.any { c -> c != ',' } }.joinToString("\n")
            }.toList()
        }
        return Regex("<table\\b.*?</table>", ignoreCase).findAll(text).map { table ->
            Regex("<tr\\b[^>]*>(.*?)</tr>", ignoreCase).findAll(table.value).map { row ->
                csvLine(Regex("<t[dh]\\b[^>]*>(.*?)</t[dh]>", ignoreCase).findAll(row.groupValues[1]).map { plain(it.groupValues[1]) }.toList())
            }.filter { line -> line.any { it != ',' } }.joinToString("\n")
        }.toList()
    }

    private fun plain(html: String): String = decode(html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ").replace(Regex("<[^>]+>"), "")).replace('\u00A0', ' ').trim()

    private fun decode(text: String): String = text.replace(Regex("&(#x?[0-9A-Fa-f]+|[A-Za-z]+);")) { m ->
        val name = m.groupValues[1]
        when {
            name == "amp" -> "&"; name == "lt" -> "<"; name == "gt" -> ">"; name == "quot" -> "\""; name == "apos" -> "'"; name == "nbsp" -> " "
            name.startsWith("#x") -> name.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            name.startsWith("#") -> name.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> m.value
        }
    }
}
