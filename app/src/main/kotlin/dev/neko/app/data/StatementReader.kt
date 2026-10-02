package dev.neko.app.data

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.neko.core.Spreadsheets
import dev.neko.core.StatementParse
import dev.neko.core.StatementParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Raised for problems the user can fix: a PDF that needs its password, or a file that holds no readable transactions. */
class StatementException(message: String) : Exception(message)

/** Turns a chosen statement file (CSV, TSV, text or PDF) into transaction rows, entirely on the phone. */
object StatementReader {
    // PDFBox holds the file, its object tree and the extracted text in memory at once, so the limit is modest for phones.
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val TOO_BIG = "That file is too big for this phone. Export a shorter date range, or use the CSV download from net banking."
    private const val NET_BANKING_HINT = "If you can, download the CSV or Excel-as-CSV version of the statement from net banking, which reads more reliably."

    suspend fun read(context: Context, uri: Uri, pdfPassword: String): StatementParse = withContext(Dispatchers.IO) {
        try {
            val bytes = try { readLimited(context, uri) }
            catch (_: java.io.IOException) { null } catch (_: SecurityException) { null } // the file grant is gone (for example after the app was restarted)
                ?: throw StatementException("I couldn't open that file. Please pick it again.")
            if (bytes.size > MAX_BYTES) throw StatementException(TOO_BIG)
            val isPdf = isPdf(bytes)
            // A workbook can have several sheets (summary, transactions, ...); the one that yields the most transactions wins.
            val texts: List<String> = when {
                isPdf -> listOf(pdfText(context, bytes, pdfPassword))
                Spreadsheets.isZip(bytes) -> Spreadsheets.xlsxSheetsAsCsv(bytes) ?: throw StatementException("I couldn't open that Excel file. If it is password protected, open it in Excel and save it as CSV. $NET_BANKING_HINT")
                Spreadsheets.isOle(bytes) -> xlsSheets(bytes)
                looksLikeMarkup(bytes) -> decode(bytes).let { page -> Spreadsheets.htmlTablesAsCsv(page).ifEmpty { listOf(page) } } // many banks save an HTML table as ".xls"
                else -> listOf(decode(bytes))
            }
            val parsed = texts.map(StatementParser::parse).maxWithOrNull(compareBy({ it.rows.size }, { -it.skipped })) ?: StatementParse(emptyList(), 0)
            if (parsed.rows.isEmpty()) {
                val lines = texts.sumOf { text -> text.lines().count { it.isNotBlank() } }
                throw StatementException(when {
                    isPdf && lines == 0 -> "This PDF has no selectable text (it may be a scanned image), so I can't read it. $NET_BANKING_HINT"
                    isPdf -> "I could read the PDF ($lines lines of text) but couldn't recognise any transactions in it. Statement layouts vary a lot between banks. $NET_BANKING_HINT"
                    parsed.skipped > 0 -> "I found ${parsed.skipped} rows but couldn't read their amounts or dates. $NET_BANKING_HINT"
                    else -> "I couldn't find any transactions in that file. Is it a bank statement with dates and amounts? $NET_BANKING_HINT"
                })
            }
            parsed
        } catch (_: OutOfMemoryError) { throw StatementException(TOO_BIG)
        } catch (_: LinkageError) { throw StatementException("The PDF reader could not start on this phone. $NET_BANKING_HINT") }
    }

    /** Reads at most [MAX_BYTES] + 1 bytes. A plain loop is used because InputStream.readNBytes only exists on Android 13 and newer. */
    private fun readLimited(context: Context, uri: Uri): ByteArray? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use { stream ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(64 * 1024)
            while (out.size() <= MAX_BYTES) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    /** True for HTML or XML text (after an optional byte-order mark and spaces). */
    private fun looksLikeMarkup(bytes: ByteArray): Boolean = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1).trimStart('﻿', 'ï', '»', '¿', ' ', '\t', '\r', '\n').startsWith("<")

    /** One CSV string per sheet of a classic binary .xls workbook. */
    private fun xlsSheets(bytes: ByteArray): List<String> {
        val protectedOrUnsupported = StatementException("I couldn't read that Excel file; it may be password protected or in an unusual format. Open it in Excel and save it as CSV, or download the CSV from net banking.")
        try {
            val workbook = jxl.Workbook.getWorkbook(ByteArrayInputStream(bytes), jxl.WorkbookSettings().apply { gcDisabled = true })
            try {
                return workbook.sheets.map { sheet ->
                    (0 until sheet.rows).map { row -> (0 until sheet.columns).map { column -> cellText(sheet.getCell(column, row)) } }
                        .filter { cells -> cells.any { it.isNotBlank() } }.joinToString("\n") { Spreadsheets.csvLine(it) }
                }
            } finally { workbook.close() }
        } catch (_: jxl.read.biff.BiffException) { throw protectedOrUnsupported
        } catch (_: java.io.IOException) { throw protectedOrUnsupported
        } catch (_: RuntimeException) { throw protectedOrUnsupported }
    }
    private fun cellText(cell: jxl.Cell): String = when (cell.type) {
        jxl.CellType.DATE, jxl.CellType.DATE_FORMULA -> (cell as jxl.DateCell).date.let { date ->
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(date)
        }
        jxl.CellType.NUMBER, jxl.CellType.NUMBER_FORMULA -> Spreadsheets.cleanNumber((cell as jxl.NumberCell).value)
        else -> cell.contents.orEmpty()
    }

    /** PDFs start with "%PDF", sometimes after a few stray bytes. */
    private fun isPdf(bytes: ByteArray): Boolean = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1).contains("%PDF-")

    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) { String(bytes, charset("windows-1252")) } // older bank exports are not UTF-8

    private fun pdfText(context: Context, bytes: ByteArray, password: String): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        try {
            PDDocument.load(ByteArrayInputStream(bytes), password).use { document ->
                return PDFTextStripper().apply { sortByPosition = true }.getText(document)
            }
        } catch (_: InvalidPasswordException) {
            throw StatementException(if (password.isEmpty()) "This PDF is password protected. Enter the password your bank gave you (often your customer ID or date of birth)." else "That password did not open the PDF.")
        } catch (error: StatementException) { throw error
        } catch (_: Exception) { throw StatementException("I couldn't read that PDF. $NET_BANKING_HINT") }
    }
}
