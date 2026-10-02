package dev.neko.core

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The user in a split. People are identified by name; this name is reserved. */
const val ME = "You"

enum class SplitMethod(val label: String) { EQUAL("Equally"), EXACT("Exact ₹"), PERCENT("Percent"), SHARES("Shares") }
enum class Repeat(val label: String) { NONE("Never"), WEEKLY("Every week"), MONTHLY("Every month") }

/**
 * A shared expense. [paidBy] paid [totalPaise]; [shares] is what each participant's part is (paise, adding up to the total; [ME] is
 * listed when the user takes part). [inputs] keeps what was typed for exact/percent/shares splits so the expense can be edited.
 */
data class SplitExpense(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val totalPaise: Long,
    val at: Long,
    val paidBy: String,
    val shares: Map<String, Long>,
    val method: SplitMethod = SplitMethod.EQUAL,
    val inputs: Map<String, String> = emptyMap(),
    val transactionId: String? = null,
    val groupId: String? = null,
    val category: Category = Category.OTHER,
    val notes: String = "",
    val repeat: Repeat = Repeat.NONE,
    /** The first expense of a repeating series; null for the first one itself. */
    val seriesId: String? = null,
) {
    init { require(totalPaise > 0 && shares.isNotEmpty() && shares.values.all { it >= 0 } && shares.values.sum() == totalPaise) { "The split must add up to the total" } }
}
/** [from] paid [to] back. */
data class Settlement(val id: String = UUID.randomUUID().toString(), val from: String, val to: String, val paise: Long, val at: Long, val groupId: String? = null)
data class SplitGroup(val id: String = UUID.randomUUID().toString(), val name: String, val members: List<String>)
/** [from] should pay [to] [paise]. */
data class Debt(val from: String, val to: String, val paise: Long)

/** A local Splitwise. Kept on the phone; nothing here changes personal spending. */
object Splits {
    fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

    /**
     * Divides [totalPaise] between [people] by [method]. [inputs] holds, per person, rupees (EXACT), percent (PERCENT) or a whole number of
     * shares (SHARES); EQUAL ignores it. Paise that do not divide evenly go to people in order, so parts always add up to the total.
     * Returns the parts, or an error the user can act on.
     */
    fun allocate(totalPaise: Long, people: List<String>, method: SplitMethod, inputs: Map<String, String>): Result<Map<String, Long>> = runCatching {
        val names = people.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        require(totalPaise > 0) { "Enter an amount above zero" }
        require(names.isNotEmpty()) { "Choose who shares this" }
        fun input(name: String) = inputs.entries.firstOrNull { same(it.key, name) }?.value?.trim().orEmpty()
        when (method) {
            SplitMethod.EQUAL -> proportional(totalPaise, names.associateWith { 1L })
            SplitMethod.EXACT -> {
                val parts = names.associateWith { name -> if (input(name).isEmpty()) 0L else Budgeting.parseRupees(input(name)) ?: require(input(name).trim().let { it == "0" || it == "0.00" }) { "Enter a valid amount for $name" }.let { 0L } }
                val left = totalPaise - parts.values.sum()
                require(left == 0L) { if (left > 0) "${Money.rupees(left)} is still unassigned" else "That is ${Money.rupees(-left)} more than the total" }
                parts
            }
            SplitMethod.PERCENT -> {
                val basis = names.associateWith { name -> input(name).ifEmpty { "0" }.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 && it.scale() <= 2 }?.movePointRight(2)?.toLong() ?: throw IllegalArgumentException("Enter a percentage for $name") }
                require(basis.values.sum() == 10_000L) { "Percentages add up to ${java.math.BigDecimal.valueOf(basis.values.sum(), 2).stripTrailingZeros().toPlainString()}%, not 100%" }
                proportional(totalPaise, basis)
            }
            SplitMethod.SHARES -> {
                val weights = names.associateWith { name -> input(name).ifEmpty { "0" }.toLongOrNull()?.takeIf { it in 0..1000 } ?: throw IllegalArgumentException("Enter whole shares for $name") }
                require(weights.values.sum() > 0) { "Give at least one person a share" }
                proportional(totalPaise, weights)
            }
        }
    }

    private fun proportional(total: Long, weights: Map<String, Long>): Map<String, Long> {
        val sum = weights.values.sum()
        val parts = LinkedHashMap(weights.mapValues { (_, w) -> total * w / sum })
        var left = total - parts.values.sum()
        // Leftover paise go to the largest remainders first (ties: in order), and never to someone with no weight.
        for (name in weights.keys.sortedByDescending { (total * weights.getValue(it)) % sum }) { if (left == 0L) break; if (weights.getValue(name) > 0) { parts[name] = parts.getValue(name) + 1; left-- } }
        return parts
    }

    /** What each friend owes the user (negative: the user owes them), across all expenses and settle-ups; settled people are left out. */
    fun balances(expenses: List<SplitExpense>, settlements: List<Settlement>): List<Pair<String, Long>> {
        val owed = LinkedHashMap<String, Pair<String, Long>>()
        fun add(person: String, paise: Long) { val key = person.trim().lowercase(); owed[key] = (owed[key]?.first ?: person.trim()) to ((owed[key]?.second ?: 0L) + paise) }
        for (e in expenses) {
            if (same(e.paidBy, ME)) e.shares.forEach { (person, part) -> if (!same(person, ME)) add(person, part) }
            else e.shares.entries.firstOrNull { same(it.key, ME) }?.let { add(e.paidBy, -it.value) }
        }
        for (s in settlements) {
            if (same(s.to, ME) && !same(s.from, ME)) add(s.from, -s.paise)
            else if (same(s.from, ME) && !same(s.to, ME)) add(s.to, s.paise)
        }
        return owed.values.filter { it.second != 0L }.sortedByDescending { it.second }
    }

    /** Each member's position in a group: positive means the group owes them. */
    fun groupNet(group: SplitGroup, expenses: List<SplitExpense>, settlements: List<Settlement>): Map<String, Long> {
        val net = LinkedHashMap<String, Long>()
        fun key(name: String) = (group.members + net.keys).firstOrNull { same(it, name) } ?: name.trim()
        group.members.forEach { net[it] = 0L }
        for (e in expenses.filter { it.groupId == group.id }) {
            net.merge(key(e.paidBy), e.totalPaise, Long::plus)
            e.shares.forEach { (person, part) -> net.merge(key(person), -part, Long::plus) }
        }
        for (s in settlements.filter { it.groupId == group.id }) { net.merge(key(s.from), s.paise, Long::plus); net.merge(key(s.to), -s.paise, Long::plus) }
        return net
    }

    /** "Simplify debts": the fewest payments that settle everyone, largest debts first. */
    fun simplify(net: Map<String, Long>): List<Debt> {
        val owe = net.filterValues { it < 0 }.mapValues { -it.value }.toMutableMap()
        val owed = net.filterValues { it > 0 }.toMutableMap()
        val debts = mutableListOf<Debt>()
        while (owe.isNotEmpty() && owed.isNotEmpty()) {
            val from = owe.maxBy { it.value }.key; val to = owed.maxBy { it.value }.key
            val paise = minOf(owe.getValue(from), owed.getValue(to))
            debts += Debt(from, to, paise)
            if (owe.getValue(from) == paise) owe.remove(from) else owe[from] = owe.getValue(from) - paise
            if (owed.getValue(to) == paise) owed.remove(to) else owed[to] = owed.getValue(to) - paise
        }
        return debts
    }

    /** Payments the user made in the last [days] days (today included) that are not split yet, newest first. */
    fun candidates(transactions: List<Transaction>, expenses: List<SplitExpense>, today: LocalDate, days: Int = 1): List<Transaction> {
        val split = expenses.mapNotNullTo(HashSet()) { it.transactionId }
        val from = today.minusDays(days - 1L)
        return transactions.filter {
            val day = Instant.ofEpochMilli(it.occurredAt).atZone(Ledger.india).toLocalDate()
            it.direction == Direction.DEBIT && it.transferId == null && it.id !in split && (it.status == PaymentStatus.POSTED || it.status == PaymentStatus.PENDING) && !day.isBefore(from) && !day.isAfter(today)
        }.sortedByDescending { it.occurredAt }
    }

    /** New copies of repeating expenses that have come due by [today] (each series repeats from its latest copy). */
    fun dueRepeats(expenses: List<SplitExpense>, today: LocalDate): List<SplitExpense> {
        val out = mutableListOf<SplitExpense>()
        for ((_, series) in expenses.groupBy { it.seriesId ?: it.id }) {
            var last = series.maxBy { it.at }
            if (last.repeat == Repeat.NONE) continue
            while (true) {
                val time = Instant.ofEpochMilli(last.at).atZone(Ledger.india)
                val next = if (last.repeat == Repeat.WEEKLY) time.plusWeeks(1) else time.plusMonths(1)
                if (next.toLocalDate().isAfter(today) || out.size > 500) break
                last = last.copy(id = UUID.randomUUID().toString(), at = next.toInstant().toEpochMilli(), transactionId = null, seriesId = last.seriesId ?: last.id)
                out += last
            }
        }
        return out
    }

    /** A friendly reminder the user can send to [person]. */
    fun reminder(person: String, owedPaise: Long, expenses: List<SplitExpense>): String {
        val recent = expenses.filter { same(it.paidBy, ME) && it.shares.keys.any { k -> same(k, person) } }.sortedByDescending { it.at }.take(3).map { it.title }
        return "Hi $person! Just a reminder that you owe me ${Money.rupees(owedPaise)}" + (if (recent.isEmpty()) "" else " (${recent.joinToString(", ")})") + ". Thanks!"
    }
}
