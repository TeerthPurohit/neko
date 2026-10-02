package dev.neko.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.neko.core.Budgeting
import dev.neko.core.Category
import dev.neko.core.Money

/**
 * Neko interviews the user about this month's money, one question at a time: total budget, income, then a limit per category.
 * Everything is asked and checked on the phone, so it works with or without cloud AI. Nothing is saved until the summary is confirmed.
 */
@Composable fun BudgetInterview(previousBudget: Long, animate: Boolean, onSave: (total: Long, income: Long, limits: Map<Category, Long>) -> Unit, onCancel: () -> Unit) {
    val categories = Budgeting.categoriesToAsk
    val summaryStep = 2 + categories.size
    var step by rememberSaveable { mutableIntStateOf(0) }
    var answers by rememberSaveable { mutableStateOf("") } // paise per answered step, comma separated; 0 means skipped
    var input by rememberSaveable { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val values = answers.split(',').filter { it.isNotEmpty() }.mapNotNull { it.toLongOrNull() }
    fun valueAt(index: Int) = values.getOrElse(index) { 0L }
    val total = valueAt(0)
    val income = valueAt(1)
    val limits = categories.mapIndexedNotNull { i, category -> valueAt(2 + i).takeIf { it > 0 }?.let { category to it } }.toMap()
    val plan = Budgeting.plan(total, limits)

    fun record(paise: Long) {
        answers = (values.take(step) + paise).joinToString(",")
        input = ""; problem = null; step += 1
    }
    fun submit() {
        val paise = Budgeting.parseRupees(input)
        if (paise == null) problem = "Enter an amount in rupees, like 25000" else record(paise)
    }
    fun goBack() {
        if (step == 0) return
        step -= 1; problem = null
        input = valueAt(step).takeIf { it > 0 }?.let { if (it % 100 == 0L) (it / 100).toString() else Money.decimal(it) } ?: ""
    }

    val question = when {
        step == 0 -> "Let's plan your month! What's your total budget for this month?" + if (previousBudget > 0) " Last time it was ${rupees(previousBudget)}." else ""
        step == 1 -> "Lovely! Roughly how much do you earn in a month? You can skip this one."
        step < summaryStep -> "How much should I allow for ${categories[step - 2].label}? You've planned ${rupees(plan.allocated)} of ${rupees(total)} so far."
        else -> buildString {
            append("All set! Your budget is ${rupees(total)}. ${rupees(plan.allocated)} is planned across ${limits.size} categor${if (limits.size == 1) "y" else "ies"}")
            append(if (plan.overAllocated) ", which is more than your total. You may want to start over." else ", leaving ${rupees(plan.unallocated)} free.")
        }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ComicBubble(question, Modifier.fillMaxWidth(), tailX = 0.56f, animate = animate)
        NekoCat(Modifier.size(170.dp, 187.dp), animate = animate, mood = if (step == summaryStep) CatMood.HAPPY else CatMood.THINKING)
        if (step < summaryStep) {
            Text("Question ${step + 1} of $summaryStep", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = input, onValueChange = { input = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(14); problem = null },
                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Amount in rupees") }, prefix = { Text("₹ ") },
                isError = problem != null, supportingText = { problem?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { submit() }),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) TextButton({ goBack() }) { Text("Back") } else TextButton(onCancel) { Text("Not now") }
                Spacer(Modifier.weight(1f))
                if (step >= 1) OutlinedButton({ record(0L) }) { Text("Skip") }
                PrimaryButton("Next", { submit() }, enabled = input.isNotBlank())
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ step = 0; answers = ""; input = ""; problem = null }) { Text("Start over") }
                Spacer(Modifier.weight(1f))
                PrimaryButton("Save my budget", { onSave(total, income, limits) }, enabled = total > 0)
            }
        }
    }
}
