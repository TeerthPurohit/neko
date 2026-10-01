package dev.neko.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

enum class Direction { DEBIT, CREDIT }
enum class PaymentStatus { POSTED, PENDING, FAILED, REVERSED }
enum class ReviewStatus { DRAFT, CONFIRMED }
enum class Source { SMS, MANUAL, AA }
enum class SpendingTreatment {
    AUTO, REVIEW_REQUIRED, PERSONAL_SPENDING, FRIEND_REIMBURSEMENT, FUNDING, INCOME,
    FD_PRINCIPAL, INVESTMENT_PRINCIPAL, INVESTMENT_RETURN, REFUND, TEMPORARY_MOVEMENT
}
enum class Category(val label: String) {
    FOOD("Food & drinks"), GROCERIES("Groceries"), TRANSPORT("Transport"), SHOPPING("Shopping"),
    BILLS("Bills & utilities"), HEALTH("Health"), ENTERTAINMENT("Entertainment"), RENT("Rent"),
    INCOME("Income"), REFUND("Refund"), TRANSFER("Own transfer"), OTHER("Other")
}

data class Transaction(
    val id: String = UUID.randomUUID().toString(),
    val occurredAt: Long,
    val amountPaise: Long,
    val direction: Direction,
    val account: String,
    val merchant: String,
    val category: Category = Category.OTHER,
    val paymentMethod: String = "UPI",
    val notes: String = "",
    val confidence: Double = 1.0,
    val source: Source = Source.MANUAL,
    val review: ReviewStatus = ReviewStatus.DRAFT,
    val status: PaymentStatus = PaymentStatus.POSTED,
    val reference: String? = null,
    val fingerprint: String = id,
    val transferId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val aiSuggestedCategory: Category? = null,
    val aiConfidence: Double? = null,
    val revision: Int = 1,
    val spendingTreatment: SpendingTreatment = SpendingTreatment.AUTO,
    val relatedTransactionId: String? = null,
    val principalPaise: Long? = null,
) {
    init { require(amountPaise > 0); require(confidence in 0.0..1.0); require(principalPaise == null || principalPaise in 0..amountPaise) }
    val countsInReports: Boolean get() = status == PaymentStatus.POSTED && review == ReviewStatus.CONFIRMED && transferId == null
}

data class Budget(val category: Category, val amountPaise: Long)
data class MonthlyReport(
    val spending: Long, val income: Long, val refunds: Long, val categories: Map<Category, Long>, val drafts: Int,
    val reimbursements: Long = 0, val funding: Long = 0, val investmentGains: Long = 0,
) {
    val netSpending: Long get() = (spending - refunds - reimbursements).coerceAtLeast(0)
}

object SpendingPolicy {
    fun treatment(tx: Transaction): SpendingTreatment = when (tx.spendingTreatment) {
        SpendingTreatment.AUTO -> when {
            tx.direction == Direction.DEBIT -> SpendingTreatment.PERSONAL_SPENDING
            tx.merchant.contains("Suresh Purohit", ignoreCase = true) -> SpendingTreatment.FUNDING
            tx.category == Category.REFUND -> SpendingTreatment.REFUND
            tx.category == Category.INCOME -> SpendingTreatment.INCOME
            else -> SpendingTreatment.REVIEW_REQUIRED
        }
        else -> tx.spendingTreatment
    }
}

object Money {
    fun parse(text: String): Long = BigDecimal(text.replace(",", "").trim())
        .setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact().also { require(it > 0) }
    fun decimal(paise: Long): String = BigDecimal.valueOf(paise, 2).toPlainString()
}

object Ledger {
    val india: ZoneId = ZoneId.of("Asia/Kolkata")
    fun report(transactions: List<Transaction>, month: YearMonth): MonthlyReport {
        fun monthOf(tx: Transaction) = YearMonth.from(Instant.ofEpochMilli(tx.occurredAt).atZone(india))
        val booked = transactions.filter { it.countsInReports }
        val byId = booked.associateBy { it.id }
        fun validTemporaryPair(tx: Transaction): Boolean {
            val other = tx.relatedTransactionId?.let(byId::get) ?: return false
            return other.relatedTransactionId == tx.id && other.amountPaise == tx.amountPaise &&
                other.direction != tx.direction && SpendingPolicy.treatment(other) == SpendingTreatment.TEMPORARY_MOVEMENT
        }
        val spendingDebits = booked.filter { it.direction == Direction.DEBIT && SpendingPolicy.treatment(it) == SpendingTreatment.PERSONAL_SPENDING }
        val expenses = spendingDebits.associateBy { it.id }
        val gross = spendingDebits.filter { monthOf(it) == month }.sumOf { it.amountPaise }
        val categories = spendingDebits.filter { monthOf(it) == month }.groupBy { it.category }
            .mapValues { (_, items) -> items.sumOf { it.amountPaise } }.toMutableMap()

        var refunds = 0L
        var reimbursements = 0L
        var legacyUnlinkedRefunds = 0L
        val adjusted = mutableMapOf<String, Long>()
        booked.filter { it.direction == Direction.CREDIT }.sortedBy { it.occurredAt }.forEach { credit ->
            when (SpendingPolicy.treatment(credit)) {
                SpendingTreatment.REFUND, SpendingTreatment.FRIEND_REIMBURSEMENT -> {
                    val original = credit.relatedTransactionId?.let(expenses::get)
                    if (original != null) {
                        val remaining = (original.amountPaise - (adjusted[original.id] ?: 0L)).coerceAtLeast(0)
                        val applied = minOf(credit.amountPaise, remaining)
                        adjusted[original.id] = (adjusted[original.id] ?: 0L) + applied
                        if (monthOf(original) == month) {
                            if (SpendingPolicy.treatment(credit) == SpendingTreatment.REFUND) refunds += applied else reimbursements += applied
                            categories[original.category] = (categories[original.category] ?: 0L) - applied
                        }
                    } else if (credit.relatedTransactionId == null && credit.category == Category.REFUND && monthOf(credit) == month) {
                        // Keep old unlinked refund entries compatible; new entries require an expense link.
                        legacyUnlinkedRefunds += credit.amountPaise
                    }
                }
                else -> Unit
            }
        }
        refunds += legacyUnlinkedRefunds
        val netCategories = categories.mapValues { (_, amount) -> amount.coerceAtLeast(0) }.filterValues { it > 0 }
        val incomes = booked.filter { it.direction == Direction.CREDIT }.sumOf { tx ->
            when (SpendingPolicy.treatment(tx)) {
                SpendingTreatment.INCOME -> if (monthOf(tx) == month) tx.amountPaise else 0L
                SpendingTreatment.INVESTMENT_RETURN -> if (monthOf(tx) == month) tx.amountPaise - (tx.principalPaise ?: 0L) else 0L
                else -> 0L
            }
        }
        val gains = booked.filter { it.direction == Direction.CREDIT && SpendingPolicy.treatment(it) == SpendingTreatment.INVESTMENT_RETURN && monthOf(it) == month }
            .sumOf { it.amountPaise - (it.principalPaise ?: 0L) }
        val funding = booked.filter { it.direction == Direction.CREDIT && SpendingPolicy.treatment(it) == SpendingTreatment.FUNDING && monthOf(it) == month }.sumOf { it.amountPaise }
        val temporaryIds = booked.filter(::validTemporaryPair).mapTo(mutableSetOf()) { it.id }
        val excludedWithBrokenLinks = booked.count { monthOf(it) == month && SpendingPolicy.treatment(it) == SpendingTreatment.TEMPORARY_MOVEMENT && it.id !in temporaryIds }
        return MonthlyReport(
            gross, incomes, refunds, netCategories,
            transactions.count { monthOf(it) == month && it.review == ReviewStatus.DRAFT } + excludedWithBrokenLinks + booked.count { monthOf(it) == month && SpendingPolicy.treatment(it) == SpendingTreatment.REVIEW_REQUIRED },
            reimbursements, funding, gains,
        )
    }

    /** Prefer an exact reference; a unique amount/time match is only offered for explicit user review. */
    fun transferCandidate(tx: Transaction, rows: List<Transaction>, ownAccounts: Set<String>): Transaction? {
        if (tx.account !in ownAccounts || tx.status != PaymentStatus.POSTED || tx.transferId != null || tx.relatedTransactionId != null) return null
        val eligible: (Transaction) -> Boolean = { it ->
            it.id != tx.id && it.account != tx.account && it.account in ownAccounts && it.transferId == null && it.relatedTransactionId == null &&
                it.direction != tx.direction && it.amountPaise == tx.amountPaise &&
                it.status == PaymentStatus.POSTED && kotlin.math.abs(it.occurredAt - tx.occurredAt) < 86_400_000
        }
        if (!tx.reference.isNullOrBlank()) {
            rows.singleOrNull { eligible(it) && it.reference == tx.reference }?.let { return it }
        }
        // Missing references can still be offered as a candidate; the UI requires explicit confirmation.
        if (tx.reference.isNullOrBlank()) return rows.singleOrNull { eligible(it) && it.reference.isNullOrBlank() }
        return rows.singleOrNull { eligible(it) && it.reference.isNullOrBlank() }
    }

    fun csv(rows: List<Transaction>): String {
        fun cell(value: Any?): String = "\"" + (value?.toString().orEmpty().let { if (it.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'" + it else it }).replace("\"", "\"\"") + "\""
        val header = "id,date_time,amount_inr,direction,account,merchant,category,payment_method,notes,confidence,source,review,status,transfer_id,spending_treatment,related_transaction_id,principal_paise"
        return header + "\r\n" + rows.joinToString("\r\n") { t ->
            listOf(t.id, Instant.ofEpochMilli(t.occurredAt).atZone(india), Money.decimal(t.amountPaise), t.direction, t.account, t.merchant, t.category.label, t.paymentMethod, t.notes, t.confidence, t.source, t.review, t.status, t.transferId, t.spendingTreatment, t.relatedTransactionId, t.principalPaise).joinToString(",") { cell(it) }
        }
    }
}

fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
