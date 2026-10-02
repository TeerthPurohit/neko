package dev.neko.core

import kotlin.math.abs

/** Recognises money moved between the user's own accounts so it is not counted as spending or income. */
object Transfers {
    private const val MINUTE = 60_000L
    private val placeholders = setOf("upi app", "cash", "manual")
    /** A real, identifiable bank account (not "Cash", the "UPI app" placeholder, or an unidentified SMS account). */
    fun isIdentified(account: String): Boolean = account.isNotBlank() && !account.contains("unidentified", ignoreCase = true) && account.lowercase() !in placeholders

    /**
     * True when a counterparty reads as the user themselves: "self", "own account", or every word of a name of at least two words.
     * A single shared first name is never enough, and words must match whole ("Mark" is not "Supermarket").
     */
    fun looksSelf(counterparty: String, userName: String): Boolean {
        val words = counterparty.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }.toSet()
        val text = counterparty.lowercase()
        if (Regex("\\b(self|own account|my account)\\b").containsMatchIn(text)) return true
        val name = userName.lowercase().split(Regex("[^a-z]+")).filter { it.length >= 3 }
        return name.size >= 2 && name.all { it in words }
    }

    /**
     * Pairs of entries that are very likely one transfer between the user's own accounts: opposite directions, equal amounts, different accounts,
     * both completed, and strong proof: the same bank reference; or two bank SMS within five minutes where both accounts are already known to be
     * the user's (otherwise a friend paying you at the same moment would look like a transfer); or the user's full name on a leg within two days.
     * Entries the user unlinked ([excluded]), that other rows are linked to, or that are already linked are never paired, and anything ambiguous is left
     * for the user.
     */
    fun autoPairs(rows: List<Transaction>, userName: String, ownAccounts: Set<String> = emptySet(), excluded: Set<String> = emptySet()): List<Pair<Transaction, Transaction>> {
        val referenced = rows.mapNotNull { it.relatedTransactionId }.toSet()
        val legs = rows.filter { it.status == PaymentStatus.POSTED && it.transferId == null && it.relatedTransactionId == null && isIdentified(it.account) && it.id !in excluded && it.id !in referenced }
        val debits = legs.filter { it.direction == Direction.DEBIT }
        val credits = legs.filter { it.direction == Direction.CREDIT }
        fun proof(d: Transaction, c: Transaction): Boolean {
            if (d.amountPaise != c.amountPaise || d.account == c.account) return false
            val gap = abs(d.occurredAt - c.occurredAt)
            if (!d.reference.isNullOrBlank() && d.reference == c.reference && gap <= 86_400_000L) return true
            if (d.source == Source.SMS && c.source == Source.SMS && gap <= 5 * MINUTE && d.account in ownAccounts && c.account in ownAccounts) return true
            return gap <= 2 * 86_400_000L && (looksSelf(d.merchant, userName) || looksSelf(c.merchant, userName))
        }
        val partners = debits.associateWith { d -> credits.filter { c -> proof(d, c) } }
        return partners.mapNotNull { (d, options) ->
            val c = options.singleOrNull() ?: return@mapNotNull null
            if (partners.count { (_, other) -> other.any { it.id == c.id } } == 1) d to c else null
        }
    }

    /** The most likely other leg of a transfer the user marked by hand: nearest completed opposite entry of the same amount in another real account within three days. */
    fun counterpart(tx: Transaction, rows: List<Transaction>, windowMs: Long = 3 * 86_400_000L): Transaction? =
        rows.filter { it.id != tx.id && it.direction != tx.direction && it.amountPaise == tx.amountPaise && it.account != tx.account && it.transferId == null && isIdentified(it.account) &&
            it.relatedTransactionId == null && it.status == PaymentStatus.POSTED && rows.none { other -> other.relatedTransactionId == it.id } && abs(it.occurredAt - tx.occurredAt) <= windowMs }
            .minByOrNull { abs(it.occurredAt - tx.occurredAt) }
}
