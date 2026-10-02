package dev.neko.core

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * What Neko does on its own, learned from the user's own confirmed history. It acts only when the evidence is clear, and leaves a
 * reviewable draft otherwise: an unknown category, an unreadable notice, a possible duplicate, an unusual amount, money that looks like a
 * transfer or card bill, and almost all money received (refunds and repayments need a link the user must choose).
 */
object Autopilot {
    data class Decision(val category: Category, val confirm: Boolean, val reason: String)

    private val noise = setOf("upi", "pvt", "ltd", "private", "limited", "india", "the", "payment", "pay", "via", "neft", "imps", "ref")

    /** A merchant's identity without case, punctuation or boilerplate, so "SWIGGY*BANGALORE" and "Swiggy Bangalore" match. */
    fun merchantKey(merchant: String): String =
        merchant.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in noise && !it.all(Char::isDigit) }.take(2).joinToString(" ")

    private fun sameMerchant(history: List<Transaction>, merchant: String, direction: Direction): List<Transaction> {
        val key = merchantKey(merchant)
        if (key.isEmpty()) return emptyList()
        return history.filter { it.review == ReviewStatus.CONFIRMED && it.direction == direction && it.transferId == null && merchantKey(it.merchant) == key }
    }

    /** The category the user has consistently filed this merchant under (their most recent five entries all agree), or null. */
    fun learnedCategory(history: List<Transaction>, merchant: String, direction: Direction): Category? {
        val recent = sameMerchant(history, merchant, direction).sortedByDescending { it.occurredAt }.take(5)
        return recent.map { it.category }.distinct().singleOrNull()?.takeIf { it != Category.OTHER }
    }

    /**
     * Why [tx] looks unusual, or null. Compared with the median of the user's confirmed payments to the same merchant (three or more),
     * or, for a new merchant, with the median of all their recent debits.
     */
    fun unusual(tx: Transaction, history: List<Transaction>): String? {
        if (tx.direction != Direction.DEBIT) return null
        val usual = sameMerchant(history, tx.merchant, Direction.DEBIT).filter { it.id != tx.id && it.status == PaymentStatus.POSTED }.map { it.amountPaise }
        if (usual.size >= 3) {
            val median = usual.sorted()[usual.size / 2]
            return if (tx.amountPaise >= 50_000 && tx.amountPaise >= median * 5 / 2) "about ${tx.amountPaise / median}× your usual ${Money.rupees(median)} at ${tx.merchant}" else null
        }
        if (usual.isNotEmpty()) return null
        val since = tx.occurredAt - 90L * 86_400_000
        val debits = history.filter { it.id != tx.id && it.direction == Direction.DEBIT && it.review == ReviewStatus.CONFIRMED && it.status == PaymentStatus.POSTED && it.transferId == null && it.occurredAt >= since }.map { it.amountPaise }
        if (debits.size < 10) return null
        val median = debits.sorted()[debits.size / 2]
        return if (tx.amountPaise >= 500_000 && tx.amountPaise >= median * 3) "a first payment to ${tx.merchant}, and larger than most of your payments" else null
    }

    /** What to do with a freshly captured bank notice, or null to leave it exactly as captured. */
    fun decide(tx: Transaction, history: List<Transaction>, possibleDuplicate: Boolean): Decision? {
        if (tx.source != Source.SMS || tx.review != ReviewStatus.DRAFT || tx.merchant == "Unknown counterparty") return null
        val learned = learnedCategory(history, tx.merchant, tx.direction)
        val category = learned ?: tx.category.takeIf { it != Category.OTHER && tx.direction == Direction.DEBIT } ?: return null
        val confirm = tx.status == PaymentStatus.POSTED && tx.confidence >= 0.6 && !possibleDuplicate && !tx.account.endsWith("unidentified") &&
            !StatementParser.looksLikeMoneyMovement(tx.merchant) && unusual(tx, history) == null &&
            (tx.direction == Direction.DEBIT || learned == Category.INCOME) // money received is confirmed only when the user has filed this payer as income before
        val reason = if (learned != null) "you have filed ${tx.merchant} as ${category.label} before" else "${tx.merchant} is a ${category.label.lowercase()} merchant"
        return Decision(category, confirm, reason)
    }

    /** An evening note about today's payments, or null when nothing was paid today. */
    fun dailyDigest(transactions: List<Transaction>, today: LocalDate, monthBudgetPaise: Long): String? {
        fun day(tx: Transaction) = Instant.ofEpochMilli(tx.occurredAt).atZone(Ledger.india).toLocalDate()
        val paid = transactions.filter { day(it) == today && it.direction == Direction.DEBIT && it.status == PaymentStatus.POSTED && it.transferId == null }
        if (paid.isEmpty()) return null
        val drafts = paid.count { it.review == ReviewStatus.DRAFT }
        val top = paid.maxBy { it.amountPaise }
        val parts = mutableListOf("You paid ${Money.rupees(paid.sumOf { it.amountPaise })} today across ${paid.size} payment${if (paid.size == 1) "" else "s"}; the largest was ${Money.rupees(top.amountPaise)} to ${top.merchant}.")
        if (monthBudgetPaise > 0) {
            val spent = Ledger.report(transactions, YearMonth.from(today)).netSpending
            parts += "You can spend about ${Money.rupees(Budgeting.safeToSpendPerDay(monthBudgetPaise, spent, Budgeting.daysLeft(today.plusDays(1))))} a day for the rest of the month."
        }
        if (drafts > 0) parts += "$drafts of today's payments need a quick look."
        return parts.joinToString(" ")
    }

    /**
     * Budget warnings not sent before: crossing 80% and 100% of a category budget or of the month's total budget. Each comes with a key
     * ("2026-10|FOOD|80") so the caller sends it once per month.
     */
    fun budgetAlerts(transactions: List<Transaction>, budgets: List<Budget>, monthBudgetPaise: Long, month: YearMonth, sent: Set<String>): List<Pair<String, String>> {
        val report = Ledger.report(transactions, month)
        val limits = budgets.map { Triple(it.category.name, it.category.label, it.amountPaise to (report.categories[it.category] ?: 0L)) } +
            listOfNotNull(if (monthBudgetPaise > 0) Triple("MONTH", "this month's budget", monthBudgetPaise to report.netSpending) else null)
        return limits.mapNotNull { (key, label, amounts) ->
            val (limit, spent) = amounts
            val level = listOf(100, 80).firstOrNull { spent * 100 >= limit * it } ?: return@mapNotNull null
            val id = "$month|$key|$level"
            if (id in sent) null
            else id to if (level == 100) "You've gone past ${label.lowercase().let { if (key == "MONTH") it else "your $it budget" }}: ${Money.rupees(spent)} of ${Money.rupees(limit)}."
                else "You've used ${spent * 100 / limit}% of ${if (key == "MONTH") label else "your ${label.lowercase()} budget"} (${Money.rupees(spent)} of ${Money.rupees(limit)})."
        }
    }
}
