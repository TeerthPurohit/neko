package dev.neko.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals

class SmsParserRegressionTest {
    private val parser = SmsParser()
    private val receivedAt = java.time.Instant.parse("2026-10-02T10:00:00Z").toEpochMilli()

    @Test fun `security footer mentioning otp does not discard a valid debit`() {
        val parsed = parser.parse(BankSms(
            "JD-ICICIT-S",
            "INR 1,250.00 debited from A/c XX1234 to Swiggy UPI Ref 612345678901. Do not share your OTP.",
            receivedAt,
        ))
        assertNotNull(parsed)
        assertEquals(125_000,parsed.amountPaise)
        assertEquals(Direction.DEBIT,parsed.direction)
    }

    @Test fun `pending and posted notices with a shared reference stay one payment without account suffix`() {
        val pending = parser.parse(BankSms("VM-ICICIB","INR 100 payment of to Cafe pending UPI Ref 612345678901",receivedAt))!!
        val posted = parser.parse(BankSms("VM-ICICIB","INR 100 debited to Cafe UPI Ref 612345678901",receivedAt+1_000))!!
        assertEquals(PaymentStatus.PENDING,pending.status)
        assertEquals(pending.fingerprint,posted.fingerprint)
    }

    @Test fun `two identical transaction notices received at different times are not silently collapsed`() {
        val first = parser.parse(BankSms("VM-ICICIB","INR 100 debited to Cafe UPI",receivedAt))!!
        val second = parser.parse(BankSms("VM-ICICIB","INR 100 debited to Cafe UPI",receivedAt+60*60*1_000))!!
        assertNotEquals(first.fingerprint,second.fingerprint,"a repeated amount and merchant can be a second payment when the bank omits a reference")
    }

    @Test fun `known bank debit in hindi is retained as a reviewable transaction`() {
        val parsed = parser.parse(BankSms("VM-ICICIB","INR 100.00 खाते से डेबिट हुआ",receivedAt))
        assertNotNull(parsed)
    }
}
