package dev.neko.core

import java.time.LocalDate
import kotlin.test.*

class RecordMatchingTest {
    private fun at(day: Int, hour: Int) = LocalDate.of(2026, 10, day).atTime(hour, 0).atZone(Ledger.india).toInstant().toEpochMilli()
    private fun candidate(name: String, reference: String?, day: Int, hour: Int) = RecordMatching.Candidate(name, reference, at(day, hour))

    @Test fun `a matching reference always wins over a closer time`() {
        val best = RecordMatching.best(listOf(candidate("near", null, 2, 12), candidate("ref", "612345678901", 3, 20)), "612345678901", at(2, 12))
        assertEquals("ref", best?.item)
    }
    @Test fun `two different references are two different payments`() {
        assertNull(RecordMatching.best(listOf(candidate("yesterday", "612345678999", 1, 12)), "612345678901", at(2, 12)))
    }
    @Test fun `without references the nearest record on the same or the adjacent day is chosen`() {
        val best = RecordMatching.best(listOf(candidate("far", null, 3, 23), candidate("near", null, 2, 10)), null, at(2, 12))
        assertEquals("near", best?.item)
        assertNull(RecordMatching.best(listOf(candidate("two days away", null, 4, 12)), null, at(2, 12)))
    }
    @Test fun `a record with a reference can still match a statement row that has none`() {
        assertEquals("a", RecordMatching.best(listOf(candidate("a", "612345678901", 2, 9)), null, at(2, 12))?.item)
    }
    @Test fun `transfers card bills and investments are not treated as ordinary spending`() {
        listOf("CRED CLUB", "HDFC CREDIT CARD PAYMENT", "Self transfer", "ZERODHA BROKING", "Paytm wallet top up", "FD booking", "AMAZON PAY WALLET").forEach {
            assertTrue(StatementParser.looksLikeMoneyMovement(it), it)
        }
        listOf("ZOMATO", "Ravi Kumar", "SWIGGY BANGALORE", "Credit Suisse Cafe").forEach { assertFalse(StatementParser.looksLikeMoneyMovement(it), it) }
    }
}
