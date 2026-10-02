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
    private val dateFormats = listOf("dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "d/M/yyyy", "d-M-yyyy", "dd/MM/yy", "dd-MM-yy", "d/M/yy", "dd MMM yyyy", "dd-MMM-yyyy", "dd/MMM/yyyy", "d MMM yyyy", "d-MMM-yyyy", "dd MMM yy", "dd-MMM-yy", "d-MMM-yy", "d MMM yy", "dd/MMM/yy", "yyyy-MM-dd", "yyyy/MM/dd")
        .map { DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH) }
    private fun tryDate(text: String): LocalDate? = dateFormats.firstNotNullOfOrNull { f -> try { LocalDate.parse(text, f) } catch (_: Exception) { null } }
    fun parseDate(cell: String): LocalDate? {
        val trimmed = cell.trim().replace(Regex("(?i)(?<=\\d[-/ ])sept(?=[-/ ]\\d)"), "Sep")
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
        val date = find(skip, Regex("^(transaction|txn|trans|tran)\\.? ?date"), Regex("^date$"), Regex("^(value|posting|post) ?date"), Regex("date"))
        val desc = find(skip, Regex("narration|description|particulars|remarks|details"))
        val debit = find(skip, Regex("debit|withdrawal|paid out"), Regex("^dr( amount)?$"))
        val credit = find(skip + debit, Regex("credit|deposit|paid in"), Regex("^cr( amount)?$"))
        val amount = find(skip + debit + credit, Regex("^amount( ?\\(.*\\))?$"), Regex("^(transaction )?amount"))
        if (date < 0 || desc < 0 || (debit < 0 && credit < 0 && amount < 0)) return null
        return Columns(date, desc, debit, credit, amount, type)
    }

    private val summaryWord = Regex("total|closing|opening|balance|b/?f\\b|brought forward|carried forward|page \\d", RegexOption.IGNORE_CASE)
    private class Draft(val date: LocalDate, val description: StringBuilder, val amountPaise: Long, val direction: Direction)

    private fun parseTable(text: String): StatementParse? {
        val lines = text.lines()
        for (delimiter in listOf(',', ';', '\t', '|')) {
            val headerAt = lines.take(40).indexOfFirst { line -> line.contains(delimiter) && findColumns(splitCsv(line, delimiter)) != null }
            if (headerAt < 0) continue
            val col = findColumns(splitCsv(lines[headerAt], delimiter))!!
            val drafts = ArrayList<Draft>(); var skipped = 0; var canContinue = false
            for (line in lines.drop(headerAt + 1)) {
                if (line.isBlank()) continue
                val cells = splitCsv(line, delimiter)
                fun cell(i: Int) = if (i >= 0) cells.getOrNull(i).orEmpty() else ""
                val date = parseDate(cell(col.date))
                if (date == null) {
                    // Totals, balances and page noise are ignored; a row carrying money but no readable date is reported, never silently dropped.
                    val summary = cells.any { summaryWord.containsMatchIn(it) }
                    val hasMoney = listOf(col.debit, col.credit, col.amount).any { it >= 0 && amountOf(cell(it)) != null }
                    if (!summary && hasMoney) { skipped++; canContinue = false; continue }
                    // A line with only a description continues the previous narration, but never one belonging to a skipped row.
                    val onlyDescription = cell(col.desc).isNotBlank() && cells.indices.none { it != col.desc && cells[it].isNotBlank() }
                    if (onlyDescription && canContinue && drafts.isNotEmpty()) drafts.last().description.append(' ').append(cell(col.desc).trim())
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
                if (resolved == null) { skipped++; canContinue = false; continue }
                drafts += Draft(date, StringBuilder(cell(col.desc).trim()), resolved.first, resolved.second); canContinue = true
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
    // A row may start with a serial number ("12  02/10/2026 ...").
    private val lineStart = Regex("^\\s*(?:\\d{1,4}[.)]?\\s+)?(\\d{1,2}[/\\-. ](?:\\d{1,2}|[A-Za-z]{3,4})[/\\-. ]\\d{2,4})\\b(.*)$")
    private val moneyToken = Regex("(?<![\\w.])-?\\d[\\d,]*\\.\\d{2}(?!\\d)(?:\\s?(?:Dr|Cr)\\b)?", RegexOption.IGNORE_CASE)
    private val openingLine = Regex("opening balance|balance b/?f|brought forward", RegexOption.IGNORE_CASE)
    private val noiseLine = Regex("page|statement|opening|closing|total|date|balance|ifsc|branch|customer", RegexOption.IGNORE_CASE)
    // "credit" alone is deliberately absent: "CREDIT CARD BILL PAY" is money going out.
    private val creditWords = Regex("credited|received|salary|refund|interest|deposit|reversal|cashback|inward|by transfer|\\bcr\\b", RegexOption.IGNORE_CASE)
    private val debitWords = Regex("debited|paid|payment|purchase|withdraw|\\batm\\b|\\bpos\\b|upi/dr|\\bdr\\b|\\bbill|charges|\\bfee", RegexOption.IGNORE_CASE)
    private val leadingDate = Regex("^\\d{1,2}[/\\-. ](?:\\d{1,2}|[A-Za-z]{3,4})[/\\-. ]\\d{2,4}\\s+")

    private class Entry(val date: LocalDate, val description: StringBuilder, val amount: Amount, val balance: Long?)
    private class Waiting(val date: LocalDate, val text: StringBuilder, var lines: Int = 0)

    private fun parseLines(text: String): StatementParse {
        val entries = ArrayList<Entry>(); var skipped = 0; var opening: Long? = null; var waiting: Waiting? = null
        // Returns false when the text has no amounts yet (the row's numbers may be on a following line).
        fun add(date: LocalDate, rest: String): Boolean {
            val tokens = moneyToken.findAll(rest).toList()
            if (tokens.isEmpty()) return false
            val amountToken = if (tokens.size >= 2) tokens[tokens.size - 2] else tokens[0]
            val amount = amountOf(amountToken.value)
            if (amount == null) { skipped++; return true }
            val balance = if (tokens.size >= 2) amountOf(tokens.last().value)?.paise else null
            entries += Entry(date, StringBuilder(rest.substring(0, amountToken.range.first).trim().replace(leadingDate, "")), amount, balance)
            return true
        }
        for (line in text.lines()) {
            if (line.isBlank()) continue
            if (openingLine.containsMatchIn(line)) { moneyToken.findAll(line).lastOrNull()?.let { amountOf(it.value)?.let { a -> opening = a.paise } }; continue }
            val start = lineStart.matchEntire(line)
            val date = start?.let { parseDate(it.groupValues[1]) }
            if (start == null || date == null) {
                val w = waiting
                if (w != null && noiseLine.containsMatchIn(line)) {
                    // A footer or totals line means the dated line above it was not a transaction row after all.
                    skipped++; waiting = null
                } else if (w != null) {
                    // Some statements wrap a row so the amounts land on a later line; collect up to three more lines for it.
                    w.text.append(' ').append(line.trim()); w.lines++
                    if (add(w.date, w.text.toString())) waiting = null else if (w.lines >= 3) { skipped++; waiting = null }
                } else if (entries.isNotEmpty() && !moneyToken.containsMatchIn(line) && !noiseLine.containsMatchIn(line)) entries.last().description.append(' ').append(line.trim())
                continue
            }
            if (waiting != null) { skipped++; waiting = null }
            val rest = start.groupValues[2]
            if (!add(date, rest)) waiting = Waiting(date, StringBuilder(rest.trim()))
        }
        if (waiting != null) skipped++
        // Statements list either oldest first or newest first. Whichever order makes the balances chain tells which way to read them.
        fun chains(newer: Entry, older: Entry): Boolean {
            val a = newer.balance ?: return false; val b = older.balance ?: return false
            return a == b + newer.amount.paise || a == b - newer.amount.paise
        }
        var forward = 0; var backward = 0
        for (i in 1 until entries.size) {
            if (chains(entries[i], entries[i - 1])) forward++
            if (chains(entries[i - 1], entries[i])) backward++
        }
        val newestFirst = backward > forward
        val rows = ArrayList<StatementRow>()
        for ((i, e) in entries.withIndex()) {
            val older: Long? = if (newestFirst) (if (i + 1 < entries.size) entries[i + 1].balance else opening) else (if (i > 0) entries[i - 1].balance else opening)
            val direction = e.amount.hint ?: when {
                older != null && e.balance != null && e.balance == older + e.amount.paise -> Direction.CREDIT
                older != null && e.balance != null && e.balance == older - e.amount.paise -> Direction.DEBIT
                creditWords.containsMatchIn(e.description) -> Direction.CREDIT
                debitWords.containsMatchIn(e.description) -> Direction.DEBIT
                else -> null
            }
            // Money direction decides what is counted as spending, so a row that cannot be proven is reported instead of guessed.
            if (direction == null) { skipped++; continue }
            rows += finish(Draft(e.date, e.description, e.amount.paise, direction))
        }
        return StatementParse(rows, skipped)
    }

    /** True for narrations that usually move money rather than spend it: own-account transfers, card bill payments, wallet top-ups and investments. */
    fun looksLikeMoneyMovement(text: String): Boolean =
        Regex("\\b(credit card|cc pay(?:ment)?|card payment|card bill|cred|own account|self transfer|self|wallet|fixed deposit|recurring deposit|fd|mutual fund|zerodha|groww|investment|sip)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

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
        return (merchant?.takeIf { it.isNotBlank() } ?: d.take(40).ifBlank { "Unknown counterparty" }) to reference
    }
}
