package dev.neko.core

import java.time.LocalDate
import kotlin.test.*

class BudgetingTest {
    @Test fun `rupee input accepts symbols commas and paise`() {
        assertEquals(2_500_000L, Budgeting.parseRupees("25000"))
        assertEquals(2_500_000L, Budgeting.parseRupees("₹ 25,000"))
        assertEquals(2_500_000L, Budgeting.parseRupees("Rs. 25,000.00"))
        assertEquals(1_250L, Budgeting.parseRupees("12.5"))
    }
    @Test fun `rupee input rejects junk zero negative and oversized values`() {
        listOf("", "abc", "0", "-5", "12.345", "1e5", "10000000.01", "25000 rupees").forEach { assertNull(Budgeting.parseRupees(it), "'$it' must be rejected") }
    }
    @Test fun `safe to spend is what remains spread over the days left and never negative`() {
        assertEquals(50_000L, Budgeting.safeToSpendPerDay(3_000_000, 1_500_000, 30))
        assertEquals(0L, Budgeting.safeToSpendPerDay(1_000_000, 1_200_000, 10))
        assertEquals(1_000_000L, Budgeting.safeToSpendPerDay(1_000_000, 0, 0), "a zero day count must not divide by zero")
    }
    @Test fun `used fraction is clamped and tolerates no budget`() {
        assertEquals(0f, Budgeting.usedFraction(0, 500))
        assertEquals(0.5f, Budgeting.usedFraction(1000, 500))
        assertEquals(1f, Budgeting.usedFraction(1000, 5000))
    }
    @Test fun `days left counts today and handles month ends`() {
        assertEquals(30, Budgeting.daysLeft(LocalDate.of(2026, 10, 2)))
        assertEquals(1, Budgeting.daysLeft(LocalDate.of(2026, 10, 31)))
        assertEquals(29, Budgeting.daysLeft(LocalDate.of(2028, 2, 1)))
    }
    @Test fun `plan summary reports what is planned and what is free`() {
        val plan = Budgeting.plan(3_000_000, mapOf(Category.FOOD to 500_000L, Category.RENT to 1_200_000L))
        assertEquals(1_700_000L, plan.allocated); assertEquals(1_300_000L, plan.unallocated); assertFalse(plan.overAllocated)
        assertTrue(Budgeting.plan(1_000_000, mapOf(Category.RENT to 1_200_000L)).overAllocated)
    }
}
