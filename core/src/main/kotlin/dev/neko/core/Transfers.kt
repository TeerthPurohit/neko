package dev.neko.core

import kotlin.math.abs

/** Recognises money moved between the user's own accounts so it is not counted as spending or income. */
object Transfers {
    private const val MINUTE = 60_000L
    private val placeholders = setOf("upi app", "cash", "manual")
    private fun identified(account: String) = account.isNotBlank() && !account.contains("unidentified", ignoreCase = true) && account.lowercase() !in placeholders

    /** True when a counterparty reads as the user themselves: "self", "own account", or every part of their name. */
    fun looksSelf(counterparty: String, userName: String): Boolean {
        val text = counterparty.lowercase()
        if (Regex("\\b(self|own account|my account)\\b").containsMatchIn(text)) return true
        val parts = userName.lowercase().split(Regex("[^a-z]+")).filter { it.length >= 4 }
        return parts.isNotEmpty() && parts.all { text.contains(it) }
    }

    private fun open(tx: Transaction) = tx.status == PaymentStatus.POSTED && tx.transferId == null && tx.relatedTransactionId == null && identified(tx.account)

    /**
     * Pairs of entries that are very likely one transfer between the user's own accounts: opposite directions, equal amounts, different accounts,
     * and strong proof: the same bank reference, two bank SMS within five minutes, or the user's own name on a leg within two days.
     * Anything ambiguous (more than one possible partner on either side) is left for the user.
     */
    fun autoPairs(rows: List<Transaction>, userName: String): List<Pair<Transaction, Transaction>> {
        val legs = rows.filter(::open)
        val debits = legs.filter { it.direction == Direction.DEBIT }
        val credits = legs.filter { it.direction == Direction.CREDIT }
        fun proof(d: Transaction, c: Transaction): Boolean {
            if (d.amountPaise != c.amountPaise || d.account == c.account) return false
            val gap = abs(d.occurredAt - c.occurredAt)
            if (!d.reference.isNullOrBlank() && d.reference == c.reference && gap <= 86_400_000L) return true
            if (d.source == Source.SMS && c.source == Source.SMS && gap <= 5 * MINUTE) return true
            return gap <= 2 * 86_400_000L && (looksSelf(d.merchant, userName) || looksSelf(c.merchant, userName))
        }
        val partners = debits.associateWith { d -> credits.filter { c -> proof(d, c) } }
        return partners.mapNotNull { (d, options) ->
            val c = options.singleOrNull() ?: return@mapNotNull null
            if (partners.count { (_, other) -> other.any { it.id == c.id } } == 1) d to c else null
        }
    }

    /** The most likely other leg of a transfer the user marked by hand: nearest opposite entry of the same amount in another account within three days. */
    fun counterpart(tx: Transaction, rows: List<Transaction>, windowMs: Long = 3 * 86_400_000L): Transaction? =
        rows.filter { it.id != tx.id && it.direction != tx.direction && it.amountPaise == tx.amountPaise && it.account != tx.account && it.transferId == null &&
            it.relatedTransactionId == null && it.status != PaymentStatus.FAILED && abs(it.occurredAt - tx.occurredAt) <= windowMs }
            .minByOrNull { abs(it.occurredAt - tx.occurredAt) }
}
