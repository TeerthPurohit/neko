package dev.neko.core

import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.*

class AutopilotTest {
    private val day = LocalDate.of(2026, 10, 2)
    private fun at(d: Int, hour: Int = 12) = LocalDate.of(2026, 10, d).atTime(hour, 0).atZone(Ledger.india).toInstant().toEpochMilli()
    private fun sms(merchant: String, paise: Long = 25_000, category: Category = Category.OTHER, direction: Direction = Direction.DEBIT, account: String = "ICICI · 1234", confidence: Double = 0.92) =
        Transaction(occurredAt = at(2), amountPaise = paise, direction = direction, account = account, merchant = merchant, category = category, source = Source.SMS, review = ReviewStatus.DRAFT, confidence = confidence)
    private fun confirmed(merchant: String, category: Category, paise: Long = 25_000, d: Int = 1, direction: Direction = Direction.DEBIT) =
        Transaction(occurredAt = at(d), amountPaise = paise, direction = direction, account = "ICICI · 1234", merchant = merchant, category = category, review = ReviewStatus.CONFIRMED)

    @Test fun `a merchant the user always files one way is filed and confirmed the same way`() {
        val history = listOf(confirmed("SWIGGY*BANGALORE", Category.GROCERIES))
        val decision = Autopilot.decide(sms("Swiggy Bangalore"), history, possibleDuplicate = false)!!
        assertEquals(Category.GROCERIES, decision.category)
        assertTrue(decision.confirm)
    }
    @Test fun `conflicting history is not a pattern`() {
        val history = listOf(confirmed("Ravi Kumar", Category.FOOD), confirmed("Ravi Kumar", Category.RENT, d = 2))
        assertNull(Autopilot.learnedCategory(history, "Ravi Kumar", Direction.DEBIT))
        assertNull(Autopilot.decide(sms("Ravi Kumar"), history, possibleDuplicate = false))
    }
    @Test fun `a known merchant type is confirmed without history`() {
        val decision = Autopilot.decide(sms("Zomato", category = Category.FOOD), emptyList(), possibleDuplicate = false)!!
        assertEquals(Category.FOOD, decision.category); assertTrue(decision.confirm)
    }
    @Test fun `uncertain notices stay drafts`() {
        assertFalse(Autopilot.decide(sms("Zomato", category = Category.FOOD), emptyList(), possibleDuplicate = true)!!.confirm)
        assertFalse(Autopilot.decide(sms("Zomato", category = Category.FOOD, account = "ICICI · unidentified"), emptyList(), false)!!.confirm)
        assertFalse(Autopilot.decide(sms("Zomato", category = Category.FOOD, confidence = 0.3), emptyList(), false)!!.confirm)
        assertNull(Autopilot.decide(sms("Unknown counterparty"), emptyList(), false))
        assertNull(Autopilot.decide(sms("Ravi Kumar"), emptyList(), false), "an unknown person gets no guessed category")
        assertFalse(Autopilot.decide(sms("CRED CLUB", category = Category.BILLS), emptyList(), false)!!.confirm, "card bills can be transfers")
    }
    @Test fun `money received is confirmed only for a payer already filed as income`() {
        assertNull(Autopilot.decide(sms("Acme Payroll", direction = Direction.CREDIT), emptyList(), false))
        val history = listOf(confirmed("Acme Payroll", Category.INCOME, direction = Direction.CREDIT))
        assertTrue(Autopilot.decide(sms("Acme Payroll", direction = Direction.CREDIT), history, false)!!.confirm)
        val refunds = listOf(confirmed("Amazon", Category.REFUND, direction = Direction.CREDIT))
        assertFalse(Autopilot.decide(sms("Amazon", direction = Direction.CREDIT), refunds, false)!!.confirm)
    }
    @Test fun `an unusually large payment is flagged and left for review`() {
        val history = (1..4).map { confirmed("Blue Tokai Coffee", Category.FOOD, paise = 30_000, d = it) }
        val big = sms("Blue Tokai Coffee", paise = 300_000)
        assertNotNull(Autopilot.unusual(big, history))
        assertFalse(Autopilot.decide(big, history, false)!!.confirm)
        assertNull(Autopilot.unusual(sms("Blue Tokai Coffee", paise = 32_000), history))
    }
    @Test fun `daily digest sums today's payments and is silent on a quiet day`() {
        val rows = listOf(confirmed("Zomato", Category.FOOD, 40_000, d = 2), confirmed("Uber", Category.TRANSPORT, 20_000, d = 2), confirmed("Old", Category.FOOD, d = 1))
        val digest = Autopilot.dailyDigest(rows, day, 0)!!
        assertTrue(digest.contains("2 payments") && digest.contains("Zomato"), digest)
        assertNull(Autopilot.dailyDigest(rows, day.plusDays(1), 0))
    }
    @Test fun `budget alerts fire once per threshold`() {
        val rows = listOf(confirmed("Zomato", Category.FOOD, 90_000, d = 2))
        val alerts = Autopilot.budgetAlerts(rows, listOf(Budget(Category.FOOD, 100_000)), 0, YearMonth.of(2026, 10), emptySet())
        assertEquals(listOf("2026-10|FOOD|80"), alerts.map { it.first })
        assertTrue(Autopilot.budgetAlerts(rows, listOf(Budget(Category.FOOD, 100_000)), 0, YearMonth.of(2026, 10), setOf("2026-10|FOOD|80")).isEmpty())
        assertEquals(listOf("2026-10|MONTH|100"), Autopilot.budgetAlerts(rows, emptyList(), 50_000, YearMonth.of(2026, 10), emptySet()).map { it.first })
    }
}
