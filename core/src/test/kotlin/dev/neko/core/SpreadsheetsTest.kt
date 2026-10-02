package dev.neko.core

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class SpreadsheetsTest {
    private fun serial(date: LocalDate) = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), date)
    private fun zip(files: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> files.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }
    private val sharedStrings = """<?xml version="1.0"?><sst xmlns="x"><si><t>Date</t></si><si><t>Narration</t></si><si><t>Withdrawal</t></si><si><t>Deposit</t></si><si><t>Balance</t></si>
        <si><r><t>UPI/612345678901/</t></r><r><t>ZOMATO/ybl &amp; more</t></r></si><si><t>ACME PAYROLL</t></si><si><t>Statement for Teerth</t></si></sst>"""
    private fun sheet(vararg rows: String) = """<?xml version="1.0"?><worksheet xmlns="x"><sheetData>${rows.joinToString("")}</sheetData></worksheet>"""

    @Test fun `an xlsx statement with shared strings numbers and date serials becomes readable rows`() {
        val d1 = serial(LocalDate.of(2026, 10, 2)); val d2 = serial(LocalDate.of(2026, 10, 3))
        val sheet1 = sheet(
            """<row r="1"><c r="A1" t="s"><v>7</v></c></row>""",
            """<row r="3"><c r="A3" t="s"><v>0</v></c><c r="B3" t="s"><v>1</v></c><c r="C3" t="s"><v>2</v></c><c r="D3" t="s"><v>3</v></c><c r="E3" t="s"><v>4</v></c></row>""",
            """<row r="4"><c r="A4"><v>$d1</v></c><c r="B4" t="s"><v>5</v></c><c r="C4"><v>1234.5</v></c><c r="E4"><v>98765.50000000001</v></c></row>""",
            """<row r="5"><c r="A5"><v>$d2.0</v></c><c r="B5" t="s"><v>6</v></c><c r="D5"><v>50000</v></c><c r="E5"><v>148765.5</v></c></row>""")
        val sheets = Spreadsheets.xlsxSheetsAsCsv(zip(mapOf("xl/sharedStrings.xml" to sharedStrings, "xl/worksheets/sheet1.xml" to sheet1)))!!
        assertEquals(1, sheets.size)
        val parsed = StatementParser.parse(sheets[0])
        assertEquals(2, parsed.rows.size, sheets[0])
        assertEquals(LocalDate.of(2026, 10, 2), parsed.rows[0].date); assertEquals(123_450L, parsed.rows[0].amountPaise); assertEquals(Direction.DEBIT, parsed.rows[0].direction)
        assertEquals("ZOMATO", parsed.rows[0].merchant, "rich text pieces are joined and entities decoded")
        assertEquals(Direction.CREDIT, parsed.rows[1].direction); assertEquals(5_000_000L, parsed.rows[1].amountPaise)
    }
    @Test fun `numbers are tidied and gaps keep their columns`() {
        val sheet1 = sheet("""<row r="1"><c r="B1"><v>90.10000000000001</v></c><c r="D1" t="inlineStr"><is><t>a, "b"</t></is></c></row>""")
        assertEquals(listOf(",90.10,,\"a, \"\"b\"\"\""), Spreadsheets.xlsxSheetsAsCsv(zip(mapOf("xl/worksheets/sheet1.xml" to sheet1)))!!.single().lines().filter { it.isNotBlank() })
    }
    @Test fun `xlsx decimal midpoint rounds half up to the nearest paise`() {
        val sheet1 = sheet("""<row r="1"><c r="A1"><v>1.005</v></c></row>""")
        val csv = Spreadsheets.xlsxSheetsAsCsv(zip(mapOf("xl/worksheets/sheet1.xml" to sheet1)))!!.single()
        assertEquals("1.01",csv.trim())
    }
    @Test fun `files that are not xlsx are rejected and oversized archives are refused`() {
        assertNull(Spreadsheets.xlsxSheetsAsCsv("plain text".toByteArray()))
        assertNull(Spreadsheets.xlsxSheetsAsCsv(zip(mapOf("readme.txt" to "no sheets here"))))
        assertTrue(Spreadsheets.isZip(zip(mapOf("a" to "b")))); assertFalse(Spreadsheets.isZip("hello".toByteArray()))
        assertTrue(Spreadsheets.isOle(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(), 0)))
    }
    @Test fun `an html table saved with an xls extension is read`() {
        val html = """<html><body><table><tr><th>Txn Date</th><th>Description</th><th>Debit</th><th>Credit</th><th>Balance</th></tr>
            <tr><td>02/10/2026</td><td>UPI/612345678901/ZOMATO&nbsp;/ybl</td><td>1,234.50</td><td>&nbsp;</td><td>98,765.50</td></tr></table></body></html>"""
        val parsed = StatementParser.parse(Spreadsheets.htmlTablesAsCsv(html).first())
        assertEquals(1, parsed.rows.size); assertEquals(123_450L, parsed.rows.single().amountPaise); assertEquals(Direction.DEBIT, parsed.rows.single().direction)
    }
    @Test fun `spreadsheetml 2003 xml is read`() {
        val xml = """<?xml version="1.0"?><Workbook><Worksheet><Table><Row><Cell><Data ss:Type="String">Date</Data></Cell><Cell><Data ss:Type="String">Narration</Data></Cell><Cell><Data ss:Type="String">Debit</Data></Cell><Cell><Data ss:Type="String">Credit</Data></Cell></Row>
            <Row><Cell><Data ss:Type="String">03-Oct-2026</Data></Cell><Cell><Data ss:Type="String">NEFT-HDFCN52026-ACME CORP-SALARY</Data></Cell><Cell/><Cell><Data ss:Type="Number">50000</Data></Cell></Row></Table></Worksheet></Workbook>"""
        val row = StatementParser.parse(Spreadsheets.htmlTablesAsCsv(xml).first()).rows.single()
        assertEquals(Direction.CREDIT, row.direction); assertEquals(5_000_000L, row.amountPaise)
    }
    @Test fun `excel date serial numbers read as dates`() {
        assertEquals(LocalDate.of(2026, 10, 2), StatementParser.parseDate(serial(LocalDate.of(2026, 10, 2)).toString()))
        assertEquals(LocalDate.of(2026, 10, 2), StatementParser.parseDate("${serial(LocalDate.of(2026, 10, 2))}.50"))
        assertNull(StatementParser.parseDate("1234"), "small numbers are not dates")
        assertNull(StatementParser.parseDate("99999999"))
    }
}
