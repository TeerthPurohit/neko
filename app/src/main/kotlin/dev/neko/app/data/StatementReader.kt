package dev.neko.app.data

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
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
            val text = if (isPdf) pdfText(context, bytes, pdfPassword) else decode(bytes)
            val parsed = StatementParser.parse(text)
            if (parsed.rows.isEmpty()) {
                val lines = text.lines().count { it.isNotBlank() }
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
