package dev.neko.core

import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.*

class SplitsTest {
    private val today = LocalDate.of(2026, 10, 3)
    private fun at(day: Int, month: Int = 10) = LocalDate.of(2026, month, day).atTime(13, 0).atZone(Ledger.india).toInstant().toEpochMilli()
    private fun paid(id: String, day: Int = 3, direction: Direction = Direction.DEBIT) =
        Transaction(id = id, occurredAt = at(day), amountPaise = 90_000, direction = direction, account = "ICICI · 1234", merchant = "Barbeque Nation", review = ReviewStatus.CONFIRMED)
    private fun expense(paidBy: String, shares: Map<String, Long>, groupId: String? = null, repeat: Repeat = Repeat.NONE, day: Int = 3) =
        SplitExpense(title = "Dinner", totalPaise = shares.values.sum(), at = at(day), paidBy = paidBy, shares = shares, groupId = groupId, repeat = repeat)

    @Test fun `every split method adds up to the total`() {
        assertEquals(mapOf(ME to 33_334L, "Ravi" to 33_333L, "Asha" to 33_333L), Splits.allocate(100_000, listOf(ME, "Ravi", "Asha"), SplitMethod.EQUAL, emptyMap()).getOrThrow())
        assertEquals(mapOf(ME to 20_000L, "Ravi" to 80_000L), Splits.allocate(100_000, listOf(ME, "Ravi"), SplitMethod.EXACT, mapOf(ME to "200", "Ravi" to "800")).getOrThrow())
        assertEquals(mapOf(ME to 25_000L, "Ravi" to 75_000L), Splits.allocate(100_000, listOf(ME, "Ravi"), SplitMethod.PERCENT, mapOf(ME to "25", "Ravi" to "75")).getOrThrow())
        assertEquals(mapOf(ME to 33_333L, "Ravi" to 66_667L), Splits.allocate(100_000, listOf(ME, "Ravi"), SplitMethod.SHARES, mapOf(ME to "1", "Ravi" to "2")).getOrThrow())
        assertEquals(mapOf(ME to 1L, "Ravi" to 0L), Splits.allocate(1, listOf(ME, "Ravi"), SplitMethod.SHARES, mapOf(ME to "1", "Ravi" to "0")).getOrThrow(), "no paise to someone with no share")
    }
    @Test fun `uneven splits explain what is wrong`() {
        assertEquals("₹100 is still unassigned", Splits.allocate(100_000, listOf(ME, "Ravi"), SplitMethod.EXACT, mapOf(ME to "100", "Ravi" to "800")).exceptionOrNull()?.message)
        assertTrue(Splits.allocate(100_000, listOf(ME, "Ravi"), SplitMethod.PERCENT, mapOf(ME to "50", "Ravi" to "40")).exceptionOrNull()!!.message!!.contains("90%"))
        assertTrue(Splits.allocate(100_000, listOf(ME), SplitMethod.EQUAL, emptyMap()).isSuccess)
        assertTrue(Splits.allocate(100_000, emptyList(), SplitMethod.EQUAL, emptyMap()).isFailure)
    }
    @Test fun `balances cover what friends owe me and what I owe them`() {
        val expenses = listOf(expense(ME, mapOf(ME to 30_000, "Ravi" to 30_000, "Asha" to 30_000)), expense("Ravi", mapOf(ME to 10_000, "Ravi" to 10_000)))
        val settled = listOf(Settlement(from = "asha", to = ME, paise = 30_000, at = at(3)))
        assertEquals(listOf("Ravi" to 20_000L), Splits.balances(expenses, settled))
        assertEquals(listOf("Ravi" to -10_000L), Splits.balances(listOf(expenses[1]), emptyList()))
        assertEquals(emptyList(), Splits.balances(listOf(expenses[1]), listOf(Settlement(from = ME, to = "Ravi", paise = 10_000, at = 0))))
    }
    @Test fun `group debts are simplified to the fewest payments`() {
        val trip = SplitGroup("g", "Goa", listOf(ME, "Ravi", "Asha"))
        val expenses = listOf(expense(ME, mapOf(ME to 30_000, "Ravi" to 30_000, "Asha" to 30_000), "g"), expense("Ravi", mapOf(ME to 20_000, "Ravi" to 20_000, "Asha" to 20_000), "g"), expense(ME, mapOf(ME to 5_000), null))
        val net = Splits.groupNet(trip, expenses, emptyList())
        assertEquals(mapOf(ME to 40_000L, "Ravi" to 10_000L, "Asha" to -50_000L), net)
        assertEquals(listOf(Debt("Asha", ME, 40_000), Debt("Asha", "Ravi", 10_000)), Splits.simplify(net))
    }
    @Test fun `only today's unsplit payments are offered`() {
        val rows = listOf(paid("today"), paid("yesterday", day = 2), paid("received", direction = Direction.CREDIT), paid("done"), paid("transfer").copy(transferId = "t"), paid("failed").copy(status = PaymentStatus.FAILED))
        val expenses = listOf(expense(ME, mapOf(ME to 1)).copy(transactionId = "done"))
        assertEquals(listOf("today"), Splits.candidates(rows, expenses, today).map { it.id })
        assertEquals(setOf("today", "yesterday"), Splits.candidates(rows, expenses, today, days = 2).map { it.id }.toSet())
    }
    @Test fun `monthly expenses repeat from the latest copy until today`() {
        val rent = expense(ME, mapOf(ME to 50_000, "Ravi" to 50_000), repeat = Repeat.MONTHLY, day = 1).copy(at = at(1, month = 8))
        val due = Splits.dueRepeats(listOf(rent), today)
        assertEquals(2, due.size, "September and October")
        assertTrue(due.all { it.seriesId == rent.id })
        assertTrue(Splits.dueRepeats(listOf(rent) + due, today).isEmpty())
        assertTrue(Splits.dueRepeats(listOf(rent.copy(repeat = Repeat.NONE)), today).isEmpty())
    }
    @Test fun `a split payment counts only my share as spending`() {
        val dinner = paid("dinner").copy(personalSharePaise = 30_000, category = Category.FOOD)
        val repaid = Transaction(occurredAt = at(3), amountPaise = 30_000, direction = Direction.CREDIT, account = "ICICI · 1234", merchant = "Ravi", review = ReviewStatus.CONFIRMED,
            spendingTreatment = SpendingTreatment.FRIEND_REIMBURSEMENT, relatedTransactionId = "dinner")
        val report = Ledger.report(listOf(dinner, repaid), YearMonth.of(2026, 10))
        assertEquals(30_000, report.spending); assertEquals(30_000, report.netSpending, "the repayment settles the split, not my share")
        assertEquals(30_000, report.categories[Category.FOOD])
    }
    @Test fun `reminders name what the friend owes`() {
        assertEquals("Hi Ravi! Just a reminder that you owe me ₹300 (Dinner). Thanks!", Splits.reminder("Ravi", 30_000, listOf(expense(ME, mapOf(ME to 30_000, "Ravi" to 30_000)))))
    }
}
