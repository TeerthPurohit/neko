package dev.neko.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** One transaction line read from a bank statement. */
data class StatementRow(val date: LocalDate, val description: String, val amountPaise: Long, val direction: Direction, val reference: String?, val merchant: String)

/** [skipped] counts lines that looked like transactions but could not be read; they are reported, never guessed. */
data class StatementParse(val rows: List<StatementRow>, val skipped: Int) {
    fun inMonth(month: YearMonth): StatementParse = copy(rows = rows.filter { YearMonth.from(it.date) == month })
}

/**
 * Reads statement text from a bank's CSV/TSV export or from text extracted out of a PDF statement. Layouts differ between banks, so the
 * columns are found by their headers; PDF text has no columns, so money in or out is decided from the running balance.
 */
object StatementParser {
    fun parse(raw: String): StatementParse {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        parseTable(text)?.takeIf { it.rows.isNotEmpty() }?.let { return it }
        return parseLines(text)
    }

    // ---------- dates and money ----------
    private val dateFormats = listOf("dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "d/M/yyyy", "d-M-yyyy", "dd/MM/yy", "dd-MM-yy", "d/M/yy", "dd MMM yyyy", "dd-MMM-yyyy", "dd/MMM/yyyy", "d MMM yyyy", "d-MMM-yyyy", "dd MMM yy", "dd-MMM-yy", "yyyy-MM-dd", "yyyy/MM/dd")
        .map { DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH) }
    private fun tryDate(text: String): LocalDate? = dateFormats.firstNotNullOfOrNull { f -> try { LocalDate.parse(text, f) } catch (_: Exception) { null } }
    fun parseDate(cell: String): LocalDate? {
        val trimmed = cell.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(Regex("\\s+"))
        return tryDate(trimmed) ?: tryDate(parts.first()) ?: if (parts.size >= 3) tryDate(parts.take(3).joinToString(" ")) else null
    }

    private class Amount(val paise: Long, val hint: Direction?)
    private val amountPattern = Regex("^(?:₹|rs\\.?|inr)?\\s*([-(]?)\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)\\s*\\)?\\s*(dr|cr)?\\.?$", RegexOption.IGNORE_CASE)
    /** Null for blank cells, zero and unreadable text alike; callers use [isBlankAmount] to tell blank from unreadable. */
    private fun amountOf(cell: String): Amount? {
        val m = amountPattern.matchEntire(cell.trim()) ?: return null
        val paise = try { Money.parse(m.groupValues[2]) } catch (_: Exception) { return null }
        val hint = when (m.groupValues[3].lowercase()) { "dr" -> Direction.DEBIT; "cr" -> Direction.CREDIT; else -> if (m.groupValues[1].isNotEmpty()) Direction.DEBIT else null }
        return Amount(paise, hint)
    }
    private fun isBlankAmount(cell: String): Boolean = cell.isBlank() || Regex("^(₹|rs\\.?|inr)?\\s*0*(\\.0+)?$", RegexOption.IGNORE_CASE).matches(cell.trim()) || cell.trim() == "-"

    // ---------- delimited tables ----------
    private class Columns(val date: Int, val desc: Int, val debit: Int, val credit: Int, val amount: Int, val type: Int)
    private fun splitCsv(line: String, delimiter: Char): List<String> {
        val cells = ArrayList<String>(); val current = StringBuilder(); var quoted = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && line.getOrNull(i + 1) == '"' -> { current.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> { cells += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        cells += current.toString()
        return cells
    }
    private fun findColumns(cells: List<String>): Columns? {
        val c = cells.map { it.trim().lowercase() }
        fun find(skip: Set<Int>, vararg patterns: Regex): Int { for (p in patterns) { val i = c.indices.firstOrNull { it !in skip && p.containsMatchIn(c[it]) }; if (i != null) return i }; return -1 }
        val type = find(emptySet(), Regex("dr ?/ ?cr|cr ?/ ?dr|debit ?/ ?credit|credit ?/ ?debit|^(transaction )?type$"))
        val skip = setOf(type)
        val date = find(skip, Regex("^(transaction|txn|trans)\\.? ?date"), Regex("^date$"), Regex("^(value|posting|post) ?date"), Regex("date"))
        val desc = find(skip, Regex("narration|description|particulars|remarks|details"))
        val debit = find(skip, Regex("debit|withdrawal|paid out"), Regex("^dr( amount)?$"))
        val credit = find(skip + debit, Regex("credit|deposit|paid in"), Regex("^cr( amount)?$"))
        val amount = find(skip + debit + credit, Regex("^amount( ?\\(.*\\))?$"), Regex("^(transaction )?amount"))
        if (date < 0 || desc < 0 || (debit < 0 && credit < 0 && amount < 0)) return null
        return Columns(date, desc, debit, credit, amount, type)
    }

    private class Draft(val date: LocalDate, val description: StringBuilder, val amountPaise: Long, val direction: Direction)

    private fun parseTable(text: String): StatementParse? {
        val lines = text.lines()
        for (delimiter in listOf(',', ';', '\t', '|')) {
            val headerAt = lines.take(40).indexOfFirst { line -> line.contains(delimiter) && findColumns(splitCsv(line, delimiter)) != null }
            if (headerAt < 0) continue
            val col = findColumns(splitCsv(lines[headerAt], delimiter))!!
            val drafts = ArrayList<Draft>(); var skipped = 0
            for (line in lines.drop(headerAt + 1)) {
                if (line.isBlank()) continue
                val cells = splitCsv(line, delimiter)
                fun cell(i: Int) = if (i >= 0) cells.getOrNull(i).orEmpty() else ""
                val date = parseDate(cell(col.date))
                if (date == null) {
                    // A line with only a description continues the previous narration; totals, balances and page noise are ignored.
                    val onlyDescription = cell(col.desc).isNotBlank() && cells.indices.none { it != col.desc && cells[it].isNotBlank() }
                    if (onlyDescription && drafts.isNotEmpty()) drafts.last().description.append(' ').append(cell(col.desc).trim())
                    continue
                }
                val debit = if (col.debit >= 0) cell(col.debit) else ""; val credit = if (col.credit >= 0) cell(col.credit) else ""
                val resolved: Pair<Long, Direction>? = if (col.debit >= 0 || col.credit >= 0) {
                    val d = amountOf(debit); val c = amountOf(credit)
                    when {
                        d != null && c == null && isBlankAmount(credit) -> d.paise to Direction.DEBIT
                        c != null && d == null && isBlankAmount(debit) -> c.paise to Direction.CREDIT
                        d == null && c == null && isBlankAmount(debit) && isBlankAmount(credit) -> { continue } // a balance or memo line with no money
                        else -> null
                    }
                } else {
                    val a = amountOf(cell(col.amount))
                    val marker = cell(col.type).trim().lowercase()
                    val direction = when {
                        marker.startsWith("dr") || marker.startsWith("debit") || marker == "d" -> Direction.DEBIT
                        marker.startsWith("cr") || marker.startsWith("credit") || marker == "c" -> Direction.CREDIT
                        else -> a?.hint
                    }
                    if (a != null && direction != null) a.paise to direction else if (isBlankAmount(cell(col.amount))) { continue } else null
                }
                if (resolved == null) { skipped++; continue }
                drafts += Draft(date, StringBuilder(cell(col.desc).trim()), resolved.first, resolved.second)
            }
            return StatementParse(drafts.map(::finish), skipped)
        }
        return null
    }

    private fun finish(d: Draft): StatementRow {
        val description = d.description.toString().replace(Regex("\\s+"), " ").trim()
        val (merchant, reference) = merchantAndReference(description)
        return StatementRow(d.date, description, d.amountPaise, d.direction, reference, merchant)
    }

    // ---------- plain text (PDF) ----------
    private val lineStart = Regex("^\\s*(\\d{1,2}[/\\-. ](?:\\d{1,2}|[A-Za-z]{3})[/\\-. ]\\d{2,4})\\b(.*)$")
    private val moneyToken = Regex("(?<![\\w.])-?\\d[\\d,]*\\.\\d{2}(?!\\d)(?:\\s?(?:Dr|Cr)\\b)?", RegexOption.IGNORE_CASE)
    private val openingLine = Regex("opening balance|balance b/?f|brought forward", RegexOption.IGNORE_CASE)
    private val noiseLine = Regex("page|statement|opening|closing|total|date|balance|ifsc|branch|customer", RegexOption.IGNORE_CASE)
    private val creditWords = Regex("credit|received|salary|refund|interest|deposit|reversal|cashback|inward|by transfer", RegexOption.IGNORE_CASE)

    private fun parseLines(text: String): StatementParse {
        val drafts = ArrayList<Draft>(); var skipped = 0; var balance: Long? = null
        for (line in text.lines()) {
            if (line.isBlank()) continue
            if (openingLine.containsMatchIn(line)) { moneyToken.findAll(line).lastOrNull()?.let { amountOf(it.value)?.let { a -> balance = a.paise } }; continue }
            val start = lineStart.matchEntire(line)
            val date = start?.let { parseDate(it.groupValues[1]) }
            if (start == null || date == null) {
                if (drafts.isNotEmpty() && !moneyToken.containsMatchIn(line) && !noiseLine.containsMatchIn(line)) drafts.last().description.append(' ').append(line.trim())
                continue
            }
            val rest = start.groupValues[2]
            val tokens = moneyToken.findAll(rest).toList()
            if (tokens.isEmpty()) continue
            val amountToken = if (tokens.size >= 2) tokens[tokens.size - 2] else tokens[0]
            val amount = amountOf(amountToken.value) ?: run { skipped++; null } ?: continue
            val newBalance = if (tokens.size >= 2) amountOf(tokens.last().value)?.paise else null
            val description = rest.substring(0, amountToken.range.first).trim().replace(Regex("^\\d{1,2}[/\\-. ](?:\\d{1,2}|[A-Za-z]{3})[/\\-. ]\\d{2,4}\\s+"), "")
            val before = balance
            val direction = amount.hint ?: when {
                before != null && newBalance != null && before - amount.paise == newBalance -> Direction.DEBIT
                before != null && newBalance != null && before + amount.paise == newBalance -> Direction.CREDIT
                else -> if (creditWords.containsMatchIn(description)) Direction.CREDIT else Direction.DEBIT
            }
            if (newBalance != null) balance = newBalance
            drafts += Draft(date, StringBuilder(description), amount.paise, direction)
        }
        return StatementParse(drafts.map(::finish), skipped)
    }

    // ---------- narration ----------
    private fun looksLikeCode(part: String): Boolean = !part.contains(' ') && ((part.length >= 8 && part.any { it.isDigit() } && part.any { it.isLetter() }) || (part.length >= 6 && part.all { it.isDigit() }))
    private val upiHandle = Regex("^[a-z][a-z0-9.]{1,14}$")

    /** Best-effort counterparty and 12-digit UPI/bank reference from a statement narration. */
    fun merchantAndReference(description: String): Pair<String, String?> {
        val d = description.replace(Regex("\\s+"), " ").trim()
        val reference = Regex("(?<!\\d)\\d{12}(?!\\d)").find(d)?.value
            ?: Regex("(?:UTR|REF(?:ERENCE)?(?: ?NO)?)[:\\- ]*([A-Za-z0-9]{8,24})", RegexOption.IGNORE_CASE).find(d)?.groupValues?.get(1)?.uppercase()
        val upper = d.uppercase()
        val merchant: String? = when {
            upper.startsWith("UPI") -> {
                val parts = (if (d.contains('/')) d.split('/') else d.split('-')).map { it.trim() }.drop(1)
                parts.firstOrNull { p -> p.any { it.isLetter() } && !p.equals("DR", true) && !p.equals("CR", true) && !looksLikeCode(p) && !upiHandle.matches(p) }?.substringBefore('@')?.trim() ?: "UPI payment"
            }
            Regex("^(NEFT|IMPS|RTGS)").containsMatchIn(upper) -> {
                val parts = d.split(Regex("[-/]")).map { it.trim() }.drop(1)
                parts.firstOrNull { p -> p.any { it.isLetter() } && !looksLikeCode(p) }
            }
            upper.startsWith("ATM") -> "ATM withdrawal"
            upper.startsWith("POS") -> d.replace(Regex("^POS\\s+\\S+\\s+", RegexOption.IGNORE_CASE), "").trim()
            else -> null
        }
        return (merchant?.takeIf { it.isNotBlank() } ?: d.take(60).ifBlank { "Unknown counterparty" }) to reference
    }
}
