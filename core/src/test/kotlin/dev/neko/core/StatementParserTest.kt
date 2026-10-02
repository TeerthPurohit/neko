package dev.neko.core

import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.*

class StatementParserTest {
    private val iciciCsv = """
Account Number,,,XXXXXX1234
Transactions List - Teerth
S No.,Value Date,Transaction Date,Cheque Number,Transaction Remarks,Withdrawal Amount (INR ),Deposit Amount (INR ),Balance (INR )
1,02/10/2026,02/10/2026,,UPI/612345678901/ZOMATO/ybl/Payment from Ph,"1,234.50",,"90,000.00"
2,03/10/2026,03/10/2026,,UPI/DR/612345678902/Ravi Kumar/okicici/Rent,,"15,000.00","1,05,000.00"
3,04/10/2026,04/10/2026,,ATM WDL 5000 MG ROAD,"5,000.00",,"1,00,000.00"
Total,,,,,"6,234.50","15,000.00",
""".trim()

    @Test fun `bank csv with separate debit and credit columns`() {
        val parsed = StatementParser.parse(iciciCsv)
        assertEquals(3, parsed.rows.size); assertEquals(0, parsed.skipped)
        val (food, rent, atm) = parsed.rows
        assertEquals(LocalDate.of(2026, 10, 2), food.date); assertEquals(123_450L, food.amountPaise); assertEquals(Direction.DEBIT, food.direction)
        assertEquals("612345678901", food.reference); assertEquals("ZOMATO", food.merchant)
        assertEquals(Direction.CREDIT, rent.direction); assertEquals(1_500_000L, rent.amountPaise); assertEquals("Ravi Kumar", rent.merchant)
        assertEquals(500_000L, atm.amountPaise); assertEquals(Direction.DEBIT, atm.direction)
    }

    @Test fun `single amount column with a debit credit marker and named months`() {
        val csv = """
Date,Description,Ref No,Amount,Dr/Cr,Balance
04-Oct-2026,POS 436612XXXXXX1234 SWIGGY BANGALORE,,450.00,Dr,89550.00
05-Oct-2026,SALARY OCT,,"50,000.00",Cr,139550.00
""".trim()
        val rows = StatementParser.parse(csv).rows
        assertEquals(2, rows.size)
        assertEquals(Direction.DEBIT, rows[0].direction); assertEquals(LocalDate.of(2026, 10, 4), rows[0].date); assertEquals("SWIGGY BANGALORE", rows[0].merchant)
        assertEquals(Direction.CREDIT, rows[1].direction); assertEquals(5_000_000L, rows[1].amountPaise)
    }

    @Test fun `semicolon and tab separated files and rupee symbols`() {
        val tsv = "Txn Date\tNarration\tDebit\tCredit\tBalance\n01/10/26\tUPI-CAFE COFFEE-cafe@ybl\t₹ 180.00\t\t9,820.00"
        val row = StatementParser.parse(tsv).rows.single()
        assertEquals(LocalDate.of(2026, 10, 1), row.date); assertEquals(18_000L, row.amountPaise); assertEquals(Direction.DEBIT, row.direction)
        val semi = "Date;Particulars;Withdrawals;Deposits\n02-10-2026;Rent;12,000.00;"
        assertEquals(1_200_000L, StatementParser.parse(semi).rows.single().amountPaise)
    }

    @Test fun `description continued on the next line is joined and total rows are ignored`() {
        val csv = "Date,Narration,Debit,Credit,Balance\n02/10/2026,UPI/612345678903/BIG BAZAAR,500.00,,9500.00\n,GROCERIES AND HOME,,,\nClosing Balance,,,,9500.00"
        val parsed = StatementParser.parse(csv)
        assertEquals(1, parsed.rows.size)
        assertTrue(parsed.rows.single().description.contains("GROCERIES AND HOME"))
        assertEquals(0, parsed.skipped)
    }

    @Test fun `pdf text lines use the balance change to decide direction`() {
        val text = """
Statement of Account
Opening Balance 1,00,000.00
02/10/2026 UPI/612345678901/ZOMATO/ybl 1,234.50 98,765.50
03/10/2026 NEFT-HDFCN52026-ACME CORP-SALARY 50,000.00 1,48,765.50
continued narration line
Page 1 of 2
""".trim()
        val rows = StatementParser.parse(text).rows
        assertEquals(2, rows.size)
        assertEquals(Direction.DEBIT, rows[0].direction); assertEquals(123_450L, rows[0].amountPaise); assertEquals("ZOMATO", rows[0].merchant)
        assertEquals(Direction.CREDIT, rows[1].direction); assertEquals(5_000_000L, rows[1].amountPaise); assertEquals("ACME CORP", rows[1].merchant)
    }

    @Test fun `rows can be limited to one month and junk is reported not guessed`() {
        val parsed = StatementParser.parse(iciciCsv.replace("04/10/2026,04/10/2026", "04/09/2026,04/09/2026"))
        assertEquals(2, parsed.inMonth(YearMonth.of(2026, 10)).rows.size)
        assertEquals(0, StatementParser.parse("hello world\nnothing useful here").rows.size)
        assertEquals(1, StatementParser.parse("Date,Narration,Debit,Credit\n02/10/2026,ok,10.00,\n02/10/2026,bad amount,abc,").skipped)
    }

    @Test fun `newest first pdf text and a missing opening balance resolve direction from neighbouring balances`() {
        val newestFirst = """
04/10/2026 NEFT-HDFCN52026-ACME CORP-SALARY 50,000.00 1,48,765.49
03/10/2026 UPI/612345678901/ZOMATO/ybl 1,234.50 98,765.49
02/10/2026 CREDIT CARD BILL PAY 5,000.00 99,999.99
Opening Balance 1,04,999.99
""".trim()
        val rows = StatementParser.parse(newestFirst).rows.sortedBy { it.date }
        assertEquals(listOf(Direction.DEBIT, Direction.DEBIT, Direction.CREDIT), rows.map { it.direction })
        val noOpening = "02/10/2026 POS 436612XXXXXX1234 SWIGGY 1,234.50 98,765.50\n03/10/2026 ACME PAYROLL 50,000.00 1,48,765.50"
        assertEquals(listOf(Direction.DEBIT, Direction.CREDIT), StatementParser.parse(noOpening).rows.map { it.direction })
    }
    @Test fun `pdf rows with serial numbers and amounts wrapped onto a later line are read`() {
        val text = """
Sr No Date Narration Withdrawal Deposit Balance
1 02/10/2026 UPI/612345678901/ZOMATO/ybl/Payment
from Ph 1,234.50 98,765.50
2 03/10/2026 NEFT-HDFCN52026-ACME CORP-SALARY
50,000.00 1,48,765.50
Page 1 of 1
""".trim()
        val rows = StatementParser.parse(text).rows
        assertEquals(2, rows.size)
        assertEquals(LocalDate.of(2026, 10, 2), rows[0].date); assertEquals(123_450L, rows[0].amountPaise); assertEquals(Direction.DEBIT, rows[0].direction)
        assertEquals(Direction.CREDIT, rows[1].direction); assertEquals("ACME CORP", rows[1].merchant)
    }
    @Test fun `a pdf row whose direction cannot be proven is reported not guessed`() {
        val parsed = StatementParser.parse("02/10/2026 MYSTERY ENTRY 777.00")
        assertEquals(0, parsed.rows.size); assertEquals(1, parsed.skipped)
    }
    @Test fun `tran date headers short month names and unreadable dates with amounts are handled`() {
        val csv = "Tran Date,Value Date,Particulars,Withdrawals,Deposits\n30-Sept-26,01-Oct-26,UPI/612345678901/CAFE/ybl,90.00,\n??,,mystery,10.00,\n,,continuation of mystery,,"
        val parsed = StatementParser.parse(csv)
        assertEquals(LocalDate.of(2026, 9, 30), parsed.rows.single().date, "the transaction date, not the value date, is used")
        assertEquals(1, parsed.skipped)
        assertFalse(parsed.rows.single().description.contains("mystery"), "a continuation must not attach to a row that was skipped")
    }
    @Test fun `merchant and reference extraction`() {
        assertEquals("ZOMATO" to "612345678901", StatementParser.merchantAndReference("UPI/612345678901/ZOMATO/ybl/Payment from Ph"))
        assertEquals("ACME CORP" to null, StatementParser.merchantAndReference("NEFT-HDFCN52026-ACME CORP-SALARY"))
        assertEquals("SALARY OCT" to null, StatementParser.merchantAndReference("SALARY OCT"))
        assertEquals("ATM withdrawal" to null, StatementParser.merchantAndReference("ATM WDL 5000 MG ROAD"))
    }
}
