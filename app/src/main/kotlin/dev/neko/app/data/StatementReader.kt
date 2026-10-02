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
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Raised for problems the user can fix: a PDF that needs its password, or a file that holds no readable transactions. */
class StatementException(message: String) : Exception(message)

/** Turns a chosen statement file (CSV, TSV, text or PDF) into transaction rows, entirely on the phone. */
object StatementReader {
    private const val MAX_BYTES = 15 * 1024 * 1024

    suspend fun read(context: Context, uri: Uri, pdfPassword: String): StatementParse = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_BYTES + 1) } ?: throw StatementException("Could not open that file.")
        if (bytes.size > MAX_BYTES) throw StatementException("That file is larger than 15 MB. Export a shorter date range.")
        val text = if (bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "%PDF") pdfText(context, bytes, pdfPassword) else decode(bytes)
        StatementParser.parse(text).also { if (it.rows.isEmpty()) throw StatementException("I couldn't find any transactions in that file. Try the CSV export from your bank's net banking.") }
    }

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
        } catch (_: Exception) { throw StatementException("I couldn't read that PDF. Try the CSV export from your bank's net banking.") }
    }
}
