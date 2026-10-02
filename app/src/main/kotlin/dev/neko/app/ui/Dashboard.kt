package dev.neko.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.neko.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Amber = Color(0xFFF6A93B)

/** A circular gauge that fills clockwise from the top; [content] sits in the middle. */
@Composable fun RingGauge(fraction: Float, color: Color, track: Color, modifier: Modifier = Modifier, thickness: Dp = 12.dp, animate: Boolean = true, content: @Composable BoxScope.() -> Unit = {}) {
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(if (animate) 900 else 0), label = "Ring")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = thickness.toPx()
            val arc = Size(size.width - stroke, size.height - stroke)
            val corner = Offset(stroke / 2, stroke / 2)
            drawArc(track, 0f, 360f, false, corner, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            if (shown > 0.001f) drawArc(color, -90f, 360f * shown, false, corner, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        content()
    }
}

@Composable private fun StatTile(label: String, value: String, sub: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, color = valueColor, maxLines = 1)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (onClick != null) Surface(onClick, modifier, shape = NekoTokens.CardShape, color = MaterialTheme.colorScheme.surface) { body() }
    else Surface(modifier, shape = NekoTokens.CardShape, color = MaterialTheme.colorScheme.surface) { body() }
}

/** The home-screen dashboard: how much of this month's budget is used, what is safe to spend today, and where the money goes. */
@Composable fun BudgetDashboard(state: NekoState, animate: Boolean, onSetBudget: () -> Unit, onReview: () -> Unit, today: LocalDate = LocalDate.now(Ledger.india)) {
    val report = Ledger.report(state.transactions, YearMonth.from(today))
    val budget = state.monthBudgetPaise
    val spent = report.netSpending
    val fraction = Budgeting.usedFraction(budget, spent)
    val ringColor = when { budget > 0 && spent >= budget -> MaterialTheme.colorScheme.error; fraction >= 0.8f -> Amber; else -> MaterialTheme.colorScheme.primary }
    val daysLeft = Budgeting.daysLeft(today)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.weight(1.1f).fillMaxHeight(), shape = NekoTokens.CardShape, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("BUDGET USED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    RingGauge(fraction, ringColor, MaterialTheme.colorScheme.surfaceVariant, Modifier.size(112.dp).semantics {
                        contentDescription = if (budget > 0) "${spent * 100 / budget} percent of this month's budget used" else "No budget set for this month"
                    }, animate = animate) {
                        if (budget > 0) Text("${spent * 100 / budget}%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        else Text("--", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (budget > 0) Text("${rupees(spent)} of ${rupees(budget)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else TextButton(onSetBudget) { Text("Set my budget") }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("SAFE TO SPEND TODAY", if (budget > 0) rupees(Budgeting.safeToSpendPerDay(budget, spent, daysLeft)) else "--",
                    if (budget > 0) "$daysLeft days left this month" else "needs a budget", Modifier.fillMaxWidth().weight(1f),
                    valueColor = if (budget > 0 && spent >= budget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                StatTile("TO REVIEW", report.drafts.toString(), if (report.drafts == 0) "all caught up" else "tap to review", Modifier.fillMaxWidth().weight(1f), onClick = onReview)
            }
        }
        val limits = state.budgets.associate { it.category to it.amountPaise }
        val top = report.categories.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(4)
        if (top.isNotEmpty()) Panel(Modifier.fillMaxWidth()) {
            Text("WHERE IT WENT THIS MONTH", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            top.forEach { (category, amount) ->
                val limit = limits[category]
                val over = limit != null && amount > limit
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(category.label, style = MaterialTheme.typography.titleMedium)
                        Text(if (limit != null) "${rupees(amount)} / ${rupees(limit)}" else rupees(amount), color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                    }
                    LinearProgressIndicator({ if (limit != null) (amount.toFloat() / limit).coerceIn(0f, 1f) else (amount.toFloat() / report.spending.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        Modifier.fillMaxWidth().height(8.dp), color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
        Surface(Modifier.fillMaxWidth(), shape = NekoTokens.CardShape, color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(9.dp).background(if (state.paused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary, CircleShape))
                Column(Modifier.weight(1f)) {
                    Text(if (state.paused) "Neko is paused" else "Neko is watching your bank SMS", style = MaterialTheme.typography.titleMedium)
                    Text(buildString {
                        append(if (state.lastSync > 0) "Agent synced " + DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH).format(Instant.ofEpochMilli(state.lastSync).atZone(Ledger.india)) else "Agent has not synced yet")
                        if (state.pendingTasks > 0) append(" · ${state.pendingTasks} task${if (state.pendingTasks == 1) "" else "s"} running")
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
