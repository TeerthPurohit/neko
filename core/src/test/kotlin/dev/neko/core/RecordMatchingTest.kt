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
    @Test fun `without references the nearest record on the same or the adjacent day is only a possible duplicate`() {
        val near = RecordMatching.nearby(listOf(candidate("far", null, 3, 23), candidate("near", null, 2, 10)), null, at(2, 12))
        assertEquals("near", near?.item)
        assertNull(RecordMatching.nearby(listOf(candidate("two days away", null, 4, 12)), null, at(2, 12)))
        assertNull(RecordMatching.nearby(listOf(candidate("other ref", "612345678999", 2, 12)), "612345678901", at(2, 12)))
    }
    @Test fun `amount and nearby date alone are not enough to silently merge a different purchase`() {
        data class Payment(val merchant: String)
        val statementCoffee = RecordMatching.Candidate(Payment("Coffee"),null,at(2,12))
        // An SMS for a separate ₹100 taxi payment, one hour later, has no bank reference.
        val candidate = RecordMatching.best(listOf(statementCoffee),null,at(2,13))
        assertNull(candidate,"an ambiguous statement row must stay reviewable instead of swallowing the second purchase")
    }
    @Test fun `a record with a reference is only a possible duplicate of a statement row that has none`() {
        assertNull(RecordMatching.best(listOf(candidate("a", "612345678901", 2, 9)), null, at(2, 12)))
        assertEquals("a", RecordMatching.nearby(listOf(candidate("a", "612345678901", 2, 9)), null, at(2, 12))?.item)
    }
    @Test fun `transfers card bills and investments are not treated as ordinary spending`() {
        listOf("CRED CLUB", "HDFC CREDIT CARD PAYMENT", "Self transfer", "ZERODHA BROKING", "Paytm wallet top up", "FD booking", "AMAZON PAY WALLET").forEach {
            assertTrue(StatementParser.looksLikeMoneyMovement(it), it)
        }
        listOf("ZOMATO", "Ravi Kumar", "SWIGGY BANGALORE", "Credit Suisse Cafe").forEach { assertFalse(StatementParser.looksLikeMoneyMovement(it), it) }
    }
}
