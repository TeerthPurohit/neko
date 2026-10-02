package dev.neko.core

import java.time.YearMonth
import kotlin.test.*

class LedgerTest {
    private val now = java.time.Instant.parse("2026-10-01T10:00:00Z").toEpochMilli()
    @Test fun `supported banks parse amount account and reference without balance confusion`() {
        listOf("VM-ICICIB" to "ICICI", "AD-IDFCFB" to "IDFC FIRST", "AX-AUBANK" to "AU").forEach { (sender, bank) ->
            val tx = SmsParser().parse(BankSms(sender, "Rs.1,234.50 debited from A/c XX1234 to ZOMATO on 01-10-26 via UPI Ref 612345678901. Avl Bal Rs.90,000.00", now))!!
            assertEquals(123450, tx.amountPaise); assertEquals("$bank · 1234", tx.account)
            assertEquals(Direction.DEBIT, tx.direction); assertEquals(Category.FOOD, tx.category)
            assertEquals(ReviewStatus.DRAFT, tx.review); assertEquals("612345678901", tx.reference)
        }
    }
    @Test fun `trai suffixed sender ids from real bank headers are accepted`() {
        // Since 2021 Indian operators append a category suffix (-S/-T/-P/-G) to registered headers.
        listOf("JD-ICICIT-S" to "ICICI", "VM-ICICIB-T" to "ICICI", "AD-IDFCFB-S" to "IDFC FIRST", "AX-AUBANK-S" to "AU", "JM-HDFCBK-S" to "HDFC", "VK-SBIUPI-S" to "SBI", "CP-AXISBK-S" to "Axis", "BZ-KOTAKB-T" to "Kotak").forEach { (sender, bank) ->
            val tx = SmsParser().parse(BankSms(sender, "Rs.250.00 debited from A/c XX1234 to ZOMATO on 01-10-26 via UPI Ref 612345678901.", now))
            assertNotNull(tx, "sender $sender was dropped"); assertEquals("$bank · 1234", tx.account)
        }
    }
    @Test fun `lookalike senders are still rejected`() {
        listOf("FRIEND", "JD-XICICIBX-S", "9876543210", "+919876543210", "JD-ICICIBANKS", "JD-PROMO-S").forEach { sender ->
            assertNull(SmsParser().parse(BankSms(sender, "Rs.250.00 debited from A/c XX1234 to ZOMATO", now)), "sender $sender must not parse")
        }
    }
    @Test fun `otp and unrelated sender never become transactions`() {
        assertNull(SmsParser().parse(BankSms("VM-ICICIB", "OTP 123456 for payment of INR 100.00", now)))
        assertNull(SmsParser().parse(BankSms("FRIEND", "INR 100 debited from A/c XX1234", now)))
        assertNull(SmsParser().parse(BankSms("VM-ICICIB", "Available balance INR 100", now)))
    }
    @Test fun `duplicate and lifecycle notices share stable fingerprint`() {
        val parser = SmsParser()
        val pending = parser.parse(BankSms("VM-ICICIB", "INR 100 payment of A/c XX1234 to Cafe pending UPI Ref 612345678901", now))!!
        val posted = parser.parse(BankSms("VM-ICICIB", "INR 100 debited from A/c XX1234 to Cafe UPI Ref 612345678901", now + 1000))!!
        assertEquals(pending.fingerprint, posted.fingerprint)
        assertEquals(PaymentStatus.PENDING, pending.status)
    }
    @Test fun `reports exclude drafts failed pending reversed and internal transfers`() {
        val base = Transaction(occurredAt = now, amountPaise = 10000, direction = Direction.DEBIT, account = "Cash", merchant = "Lunch", review = ReviewStatus.CONFIRMED)
        val rows = listOf(base, base.copy(id="draft", review=ReviewStatus.DRAFT), base.copy(id="failed",status=PaymentStatus.FAILED), base.copy(id="pending",status=PaymentStatus.PENDING), base.copy(id="reversed",status=PaymentStatus.REVERSED), base.copy(id="transfer",transferId="pair"), base.copy(id="refund",amountPaise=2000,direction=Direction.CREDIT,category=Category.REFUND))
        val report = Ledger.report(rows, YearMonth.of(2026,10))
        assertEquals(10000, report.spending); assertEquals(8000, report.netSpending); assertEquals(0,report.income)
    }
    @Test fun `transfer matching requires known owned accounts and exact reference`() {
        val a = Transaction(occurredAt=now,amountPaise=50000,direction=Direction.DEBIT,account="ICICI",merchant="Me",reference="123456789012")
        val b = a.copy(id="other",account="AU",direction=Direction.CREDIT)
        assertEquals(b, Ledger.transferCandidate(a,listOf(a,b),setOf("ICICI","AU")))
        assertNull(Ledger.transferCandidate(a,listOf(a,b),setOf("ICICI")))
        assertNull(Ledger.transferCandidate(a.copy(reference=null),listOf(a,b),setOf("ICICI","AU")))
    }
    @Test fun `money uses exact paise and csv neutralizes spreadsheet formulas`() {
        assertEquals(123456, Money.parse("1,234.56")); assertFails { Money.parse("1.001") }
        val csv = Ledger.csv(listOf(Transaction(occurredAt=now,amountPaise=12345,direction=Direction.DEBIT,account="Cash",merchant="=HYPERLINK(\"evil\")")))
        assertContains(csv,"123.45"); assertContains(csv,"'=HYPERLINK")
    }
}
