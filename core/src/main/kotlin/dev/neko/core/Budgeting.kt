package dev.neko.core

import java.time.LocalDate

/** Plain-Kotlin budget helpers so the dashboard and the budget interview can be tested without Android. */
object Budgeting {
    /** Largest amount accepted anywhere in Neko; matches the backend's per-amount limit (in paise). */
    const val MAX_PAISE = 1_000_000_000L
    val categoriesToAsk = listOf(Category.RENT, Category.FOOD, Category.GROCERIES, Category.TRANSPORT, Category.BILLS, Category.SHOPPING, Category.HEALTH, Category.ENTERTAINMENT)

    /** Turns typed text such as "₹ 25,000" or "Rs. 1,200.50" into paise; null when it is not a positive amount within the limit. */
    fun parseRupees(text: String): Long? {
        val cleaned = text.trim().replace(Regex("^(?:₹|rs\\.?|inr)\\s*", RegexOption.IGNORE_CASE), "").replace(",", "").trim()
        if (!Regex("\\d{1,10}(\\.\\d{1,2})?").matches(cleaned)) return null
        val paise = try { Money.parse(cleaned) } catch (_: Exception) { return null }
        return paise.takeIf { it in 1..MAX_PAISE }
    }

    fun daysLeft(today: LocalDate): Int = today.lengthOfMonth() - today.dayOfMonth + 1

    /** What can be spent per remaining day without passing the budget (never negative). */
    fun safeToSpendPerDay(budgetPaise: Long, spentPaise: Long, daysLeft: Int): Long = ((budgetPaise - spentPaise).coerceAtLeast(0)) / daysLeft.coerceAtLeast(1)

    fun usedFraction(budgetPaise: Long, spentPaise: Long): Float = if (budgetPaise <= 0) 0f else (spentPaise.toFloat() / budgetPaise).coerceIn(0f, 1f)

    data class Plan(val total: Long, val allocated: Long) {
        val unallocated: Long get() = (total - allocated).coerceAtLeast(0)
        val overAllocated: Boolean get() = allocated > total
    }
    fun plan(totalPaise: Long, limits: Map<Category, Long>): Plan = Plan(totalPaise, limits.values.sum())
}
