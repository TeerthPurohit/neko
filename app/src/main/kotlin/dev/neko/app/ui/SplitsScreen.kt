package dev.neko.app.ui

import android.app.DatePickerDialog
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.neko.core.*
import java.time.Instant
import java.time.LocalDate

// Saveable state holds lists as newline-separated text and maps as "key\tvalue" lines, so dialogs survive rotation.
private fun lines(text: String) = text.split('\n').filter { it.isNotBlank() }
private fun pairs(text: String) = lines(text).associate { it.substringBefore('\t') to it.substringAfter('\t', "") }
private fun pairsText(map: Map<String, String>) = map.entries.joinToString("\n") { "${it.key}\t${it.value}" }
private fun who(name: String) = if (Splits.same(name, ME)) "You" else name

@Composable private fun Picker(label: String, value: String, options: List<Pair<String, String>>, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch)) { Text("$label: ${options.firstOrNull { it.first == value }?.second ?: value}") }
        DropdownMenu(open, { open = false }) { options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onPick(key); open = false }) } }
    }
}

/** The local Splitwise: balances with friends, groups, settle-ups, reminders and every split. Everything stays on the phone. */
@Composable fun SplitsScreen(state: NekoState, model: NekoViewModel, onSplitPayments: () -> Unit) {
    val context = LocalContext.current
    val balances = Splits.balances(state.splitExpenses, state.settlements)
    var tab by rememberSaveable { mutableStateOf("Friends") }
    var editing by remember { mutableStateOf<SplitExpense?>(null) }; var adding by rememberSaveable { mutableStateOf(false) }; var addingGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var settling by remember { mutableStateOf<Settlement?>(null) }; var friend by rememberSaveable { mutableStateOf<String?>(null) }
    var groupOpen by rememberSaveable { mutableStateOf<String?>(null) }; var groupEditing by remember { mutableStateOf<SplitGroup?>(null) }
    var removing by remember { mutableStateOf<Settlement?>(null) }; var newPerson by rememberSaveable { mutableStateOf("") }
    val owedToMe = balances.filter { it.second > 0 }.sumOf { it.second }; val iOwe = -balances.filter { it.second < 0 }.sumOf { it.second }
    LazyColumn(contentPadding = PaddingValues(NekoTokens.Page), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenTitle("Splits", "Who owes what, kept on your phone.") }
        item { Panel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.primaryContainer) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { Text("You are owed", style = MaterialTheme.typography.labelLarge); Text(rupees(owedToMe), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary) }
                Column(Modifier.weight(1f)) { Text("You owe", style = MaterialTheme.typography.labelLarge); Text(rupees(iOwe), style = MaterialTheme.typography.headlineSmall, color = if (iOwe > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Split a payment", onSplitPayments, Modifier.weight(1f))
            OutlinedButton({ addingGroupId = null; adding = true }, Modifier.weight(1f).heightIn(min = NekoTokens.Touch)) { Text("Add expense") }
        } }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Friends", "Groups", "Activity").forEach { FilterChip(tab == it, { tab = it }, label = { Text(it) }) } } }
        when (tab) {
            "Friends" -> {
                if (balances.isEmpty()) item { EmptyState("All square", "Split a payment or add an expense, and Neko keeps track of who owes whom. Neko also asks about today's payments when you open the app.", Icons.Outlined.Group) }
                items(balances, key = { it.first }) { (person, paise) -> Panel(Modifier.fillMaxWidth().clickable { friend = person }) {
                    Text(person, style = MaterialTheme.typography.titleMedium)
                    Text(if (paise > 0) "owes you ${rupees(paise)}" else "you owe ${rupees(-paise)}", color = if (paise > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ settling = if (paise > 0) Settlement(from = person, to = ME, paise = paise, at = System.currentTimeMillis()) else Settlement(from = ME, to = person, paise = -paise, at = System.currentTimeMillis()) }, enabled = !state.busy) { Text("Settle up") }
                        if (paise > 0) TextButton({ context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Splits.reminder(person, paise, state.splitExpenses)), "Remind $person")) }) { Text("Remind") }
                    }
                } }
                val square = state.people.filter { p -> balances.none { Splits.same(it.first, p) } }
                if (square.isNotEmpty()) item { Text("All square with ${square.joinToString()}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newPerson, { newPerson = it.take(40) }, Modifier.weight(1f), singleLine = true, label = { Text("Add a friend") })
                    TextButton({ model.addPerson(newPerson.trim()); newPerson = "" }, enabled = newPerson.isNotBlank() && !Splits.same(newPerson, ME)) { Text("Add") }
                } }
            }
            "Groups" -> {
                item { OutlinedButton({ groupEditing = SplitGroup(name = "", members = listOf(ME)) }, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.GroupAdd, null); Spacer(Modifier.width(8.dp)); Text("New group") } }
                if (state.splitGroups.isEmpty()) item { EmptyState("No groups yet", "Make a group for a trip, your flat or the office lunch crew. Neko shows who owes whom inside it and the fewest payments to settle up.", Icons.Outlined.Groups) }
                items(state.splitGroups, key = { it.id }) { g ->
                    val mine = Splits.groupNet(g, state.splitExpenses, state.settlements).entries.firstOrNull { Splits.same(it.key, ME) }?.value ?: 0L
                    Panel(Modifier.fillMaxWidth().clickable { groupOpen = g.id }) {
                        Text(g.name, style = MaterialTheme.typography.titleLarge)
                        Text("${g.members.size} people · " + when { mine > 0 -> "you are owed ${rupees(mine)}"; mine < 0 -> "you owe ${rupees(-mine)}"; else -> "settled up" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            else -> {
                val feed = (state.splitExpenses.map { it.at to (it as Any) } + state.settlements.map { it.at to (it as Any) }).sortedByDescending { it.first }.take(100)
                if (feed.isEmpty()) item { Text("Nothing split yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(feed, key = { (_, item) -> if (item is SplitExpense) item.id else (item as Settlement).id }) { (_, item) -> SplitFeedRow(item, state, { editing = it }, { removing = it }) }
            }
        }
        item { Text("Splitting a payment counts only your share as your spending. What friends owe, and their repayments, are tracked here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (adding) SplitEditor(state, model, null, null, addingGroupId) { adding = false }
    editing?.let { e -> SplitEditor(state, model, e, state.transactions.find { it.id == e.transactionId }, e.groupId) { editing = null } }
    settling?.let { s -> SettleDialog(state, s, { settling = null }) { model.settle(it); settling = null } }
    removing?.let { s -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove this payment?") }, text = { Text("${who(s.from)} paid ${who(s.to)} ${rupees(s.paise)}.") },
        confirmButton = { TextButton({ model.removeSplitEntry(s.id); removing = null }) { Text("Remove") } }, dismissButton = { TextButton({ removing = null }) { Text("Keep") } }) }
    friend?.let { person ->
        val shared = state.splitExpenses.filter { e -> (Splits.same(e.paidBy, person) && e.shares.keys.any { Splits.same(it, ME) }) || (Splits.same(e.paidBy, ME) && e.shares.keys.any { Splits.same(it, person) }) }
        val paid = state.settlements.filter { (Splits.same(it.from, person) && Splits.same(it.to, ME)) || (Splits.same(it.from, ME) && Splits.same(it.to, person)) }
        AlertDialog(onDismissRequest = { friend = null }, title = { Text(person) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shared.isEmpty() && paid.isEmpty()) Text("Nothing shared yet.")
            shared.sortedByDescending { it.at }.forEach { e ->
                val mine = if (Splits.same(e.paidBy, ME)) e.shares.entries.firstOrNull { Splits.same(it.key, person) }?.value ?: 0 else -(e.shares.entries.firstOrNull { Splits.same(it.key, ME) }?.value ?: 0)
                Text("${e.title} · ${dateText(e.at)}\n${if (mine >= 0) "$person owes you ${rupees(mine)}" else "you owe ${rupees(-mine)}"}")
            }
            paid.sortedByDescending { it.at }.forEach { Text("${who(it.from)} paid ${who(it.to)} ${rupees(it.paise)} · ${dateText(it.at)}") }
        } }, confirmButton = { TextButton({ friend = null }) { Text("Close") } })
    }
    groupEditing?.let { g -> GroupDialog(state, g, { groupEditing = null }) { model.saveGroup(it); groupEditing = null } }
    groupOpen?.let { id -> state.splitGroups.find { it.id == id }?.let { g ->
        GroupDetail(state, g, onClose = { groupOpen = null }, onAdd = { addingGroupId = g.id; adding = true }, onEdit = { groupEditing = g }, onExpense = { editing = it }, onSettle = { settling = it }, onDelete = { model.removeSplitEntry(g.id); groupOpen = null })
    } }
}

@Composable private fun SplitFeedRow(item: Any, state: NekoState, onEdit: (SplitExpense) -> Unit, onRemoveSettlement: (Settlement) -> Unit) {
    if (item is SplitExpense) Panel(Modifier.fillMaxWidth().clickable { onEdit(item) }) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(dateText(item.at), item.category.label, state.splitGroups.find { it.id == item.groupId }?.name, item.repeat.takeIf { it != Repeat.NONE }?.label).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(rupees(item.totalPaise), style = MaterialTheme.typography.titleMedium)
        }
        Text("${who(item.paidBy)} paid · " + item.shares.filterValues { it > 0 }.entries.joinToString(" · ") { "${who(it.key)} ${rupees(it.value)}" }, style = MaterialTheme.typography.bodySmall)
        if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else (item as Settlement).let { s -> Panel(Modifier.fillMaxWidth().clickable { onRemoveSettlement(s) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Payments, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) { Text("${who(s.from)} paid ${who(s.to)} ${rupees(s.paise)}"); Text(dateText(s.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    } }
}

/**
 * Adds or edits a split. With [transaction] (a bank payment the user made) the amount, payer and date come from the payment.
 * Supports equal, exact, percentage and share splits, any payer, a group, category, notes, date and repeating.
 */
@Composable fun SplitEditor(state: NekoState, model: NekoViewModel, initial: SplitExpense?, transaction: Transaction?, groupId: String?, onSaved: () -> Unit = {}, onClose: () -> Unit) {
    val context = LocalContext.current
    val key = initial?.id ?: transaction?.id ?: "new"
    val group0 = state.splitGroups.find { it.id == (initial?.groupId ?: groupId) }
    var title by rememberSaveable(key) { mutableStateOf(initial?.title ?: transaction?.merchant.orEmpty()) }
    var amount by rememberSaveable(key) { mutableStateOf((initial?.totalPaise ?: transaction?.amountPaise)?.let(Money::decimal).orEmpty()) }
    var paidBy by rememberSaveable(key) { mutableStateOf(initial?.paidBy ?: ME) }
    var people by rememberSaveable(key) { mutableStateOf((initial?.shares?.keys?.toList() ?: group0?.members ?: listOf(ME)).joinToString("\n")) }
    var method by rememberSaveable(key) { mutableStateOf((initial?.method ?: SplitMethod.EQUAL).name) }
    var inputs by rememberSaveable(key) { mutableStateOf(pairsText(initial?.inputs.orEmpty())) }
    var group by rememberSaveable(key) { mutableStateOf(group0?.id.orEmpty()) }
    var category by rememberSaveable(key) { mutableStateOf((initial?.category ?: transaction?.category ?: Category.OTHER).name) }
    var notes by rememberSaveable(key) { mutableStateOf(initial?.notes.orEmpty()) }
    var repeat by rememberSaveable(key) { mutableStateOf((initial?.repeat ?: Repeat.NONE).name) }
    var at by rememberSaveable(key) { mutableLongStateOf(initial?.at ?: transaction?.occurredAt ?: System.currentTimeMillis()) }
    var newName by rememberSaveable(key) { mutableStateOf("") }; var deleting by remember { mutableStateOf(false) }
    val fromBank = transaction != null
    val chosen = lines(people); val splitMethod = SplitMethod.valueOf(method)
    val total = Budgeting.parseRupees(amount)
    val allocation = total?.let { Splits.allocate(it, chosen, splitMethod, pairs(inputs)) }
    val everyone = (listOf(ME) + state.people + chosen + paidBy).distinctBy { it.trim().lowercase() }
    fun toggle(name: String) { people = (if (chosen.any { Splits.same(it, name) }) chosen.filterNot { Splits.same(it, name) } else chosen + name).joinToString("\n") }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NekoTokens.Page), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClose) { Icon(Icons.Outlined.Close, "Close") }; Text(if (initial == null) "Add a split" else "Edit split", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)); if (initial != null) TextButton({ deleting = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) } }
            OutlinedTextField(title, { title = it.take(100) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("What was it for?") })
            OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(14) }, Modifier.fillMaxWidth(), singleLine = true, enabled = !fromBank, prefix = { Text("₹ ") }, label = { Text("Total") },
                isError = amount.isNotBlank() && total == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), supportingText = { if (fromBank) Text("From your payment to ${transaction!!.merchant} on ${dateText(transaction.occurredAt)}") })
            if (state.splitGroups.isNotEmpty()) Picker("Group", group, listOf("" to "No group") + state.splitGroups.map { it.id to it.name }) { id ->
                group = id; state.splitGroups.find { it.id == id }?.let { g -> people = (g.members + if (fromBank) listOf(ME) else emptyList()).distinctBy { it.lowercase() }.joinToString("\n") }
            }
            if (!fromBank) Picker("Paid by", paidBy, everyone.map { it to who(it) }) { paidBy = it; if (chosen.none { c -> Splits.same(c, it) }) toggle(it) }
            Text("Split between", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { everyone.forEach { name -> FilterChip(chosen.any { Splits.same(it, name) }, { toggle(name) }, label = { Text(who(name)) }) } }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newName, { newName = it.take(40) }, Modifier.weight(1f), singleLine = true, label = { Text("Add a person") })
                TextButton({ val n = newName.trim(); if (chosen.none { Splits.same(it, n) }) toggle(n); newName = "" }, enabled = newName.isNotBlank() && !Splits.same(newName, ME)) { Text("Add") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { SplitMethod.entries.forEach { m -> FilterChip(splitMethod == m, { method = m.name }, label = { Text(m.label) }) } }
            if (splitMethod != SplitMethod.EQUAL) chosen.forEach { name ->
                val typed = pairs(inputs).entries.firstOrNull { Splits.same(it.key, name) }?.value.orEmpty()
                OutlinedTextField(typed, { value -> inputs = pairsText(pairs(inputs).filterKeys { !Splits.same(it, name) } + (name to value.filter { c -> c.isDigit() || c == '.' }.take(12))) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(who(name)) }, prefix = { if (splitMethod == SplitMethod.EXACT) Text("₹ ") }, suffix = { Text(when (splitMethod) { SplitMethod.PERCENT -> "%"; SplitMethod.SHARES -> "shares"; else -> "" }) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            allocation?.let { result -> Panel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.surfaceVariant) {
                result.onSuccess { parts -> parts.forEach { (name, paise) -> Row(Modifier.fillMaxWidth()) { Text(who(name), Modifier.weight(1f)); Text(rupees(paise)) } } }
                    .onFailure { Text(it.message ?: "Check the split", color = MaterialTheme.colorScheme.error) }
            } }
            CategoryPicker(Category.valueOf(category)) { category = it.name }
            if (!fromBank) OutlinedButton({ val d = Instant.ofEpochMilli(at).atZone(Ledger.india).toLocalDate()
                DatePickerDialog(context, { _, y, m, day -> at = LocalDate.of(y, m + 1, day).atTime(12, 0).atZone(Ledger.india).toInstant().toEpochMilli() }, d.year, d.monthValue - 1, d.dayOfMonth).show() }, Modifier.fillMaxWidth()) { Text("Date: ${dateText(at)}") }
            if (!fromBank) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Repeat.entries.forEach { r -> FilterChip(repeat == r.name, { repeat = r.name }, label = { Text(r.label) }) } }
            OutlinedTextField(notes, { notes = it.take(300) }, Modifier.fillMaxWidth(), label = { Text("Notes (optional)") })
            PrimaryButton("Save", {
                val parts = allocation?.getOrNull() ?: return@PrimaryButton
                model.saveExpense(SplitExpense(id = initial?.id ?: java.util.UUID.randomUUID().toString(), title = title.trim(), totalPaise = total!!, at = at, paidBy = if (fromBank) ME else paidBy, shares = parts,
                    method = splitMethod, inputs = if (splitMethod == SplitMethod.EQUAL) emptyMap() else pairs(inputs).filterKeys { k -> chosen.any { Splits.same(it, k) } },
                    transactionId = transaction?.id ?: initial?.transactionId, groupId = group.ifBlank { null }, category = Category.valueOf(category), notes = notes.trim(),
                    repeat = Repeat.valueOf(repeat), seriesId = initial?.seriesId))
                onSaved(); onClose()
            }, Modifier.fillMaxWidth(), enabled = title.isNotBlank() && allocation?.isSuccess == true && !state.busy)
        }
    } }
    if (deleting && initial != null) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete this split?") },
        text = { Text("${initial.title}, ${rupees(initial.totalPaise)}.${if (initial.transactionId != null) " The bank payment stays in your ledger and counts in full again." else ""}") },
        confirmButton = { TextButton({ model.removeExpense(initial.id); deleting = false; onClose() }) { Text("Delete") } }, dismissButton = { TextButton({ deleting = false }) { Text("Keep") } })
}

@Composable private fun SettleDialog(state: NekoState, initial: Settlement, onDismiss: () -> Unit, onSave: (Settlement) -> Unit) {
    var from by rememberSaveable(initial.id) { mutableStateOf(initial.from) }; var to by rememberSaveable(initial.id) { mutableStateOf(initial.to) }
    var amount by rememberSaveable(initial.id) { mutableStateOf(Money.decimal(initial.paise)) }
    val paise = Budgeting.parseRupees(amount)
    val everyone = (listOf(ME) + state.people + from + to).distinctBy { it.lowercase() }.map { it to who(it) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Record a payment") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Picker("From", from, everyone) { from = it }; Picker("To", to, everyone) { to = it }
        OutlinedTextField(amount, { amount = it }, prefix = { Text("₹ ") }, singleLine = true, isError = paise == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Text("Record cash or UPI paid back outside a split.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }, confirmButton = { TextButton({ onSave(initial.copy(from = from, to = to, paise = paise!!, at = System.currentTimeMillis())) }, enabled = paise != null && !Splits.same(from, to)) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable private fun GroupDialog(state: NekoState, initial: SplitGroup, onDismiss: () -> Unit, onSave: (SplitGroup) -> Unit) {
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }; var members by rememberSaveable(initial.id) { mutableStateOf(initial.members.joinToString("\n")) }
    var newName by rememberSaveable(initial.id) { mutableStateOf("") }
    val chosen = lines(members)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial.name.isEmpty()) "New group" else "Edit group") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text("Group name") })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf(ME) + state.people + chosen).distinctBy { it.lowercase() }.forEach { p -> FilterChip(chosen.any { Splits.same(it, p) }, { if (!Splits.same(p, ME)) members = (if (chosen.any { Splits.same(it, p) }) chosen.filterNot { Splits.same(it, p) } else chosen + p).joinToString("\n") }, label = { Text(who(p)) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newName, { newName = it.take(40) }, Modifier.weight(1f), singleLine = true, label = { Text("Add a person") })
            TextButton({ if (chosen.none { Splits.same(it, newName) }) members = (chosen + newName.trim()).joinToString("\n"); newName = "" }, enabled = newName.isNotBlank() && !Splits.same(newName, ME)) { Text("Add") }
        }
    } }, confirmButton = { TextButton({ onSave(initial.copy(name = name.trim(), members = chosen)) }, enabled = name.isNotBlank() && chosen.size >= 2) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable private fun GroupDetail(state: NekoState, group: SplitGroup, onClose: () -> Unit, onAdd: () -> Unit, onEdit: () -> Unit, onExpense: (SplitExpense) -> Unit, onSettle: (Settlement) -> Unit, onDelete: () -> Unit) {
    val net = Splits.groupNet(group, state.splitExpenses, state.settlements)
    val debts = Splits.simplify(net)
    val expenses = state.splitExpenses.filter { it.groupId == group.id }.sortedByDescending { it.at }
    var deleting by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(contentPadding = PaddingValues(NekoTokens.Page), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClose) { Icon(Icons.Outlined.Close, "Close") }; Text(group.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)); IconButton(onEdit) { Icon(Icons.Outlined.Edit, "Edit group") } } }
            item { Text(group.members.joinToString { who(it) }, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Text("Total spent ${rupees(expenses.sumOf { it.totalPaise })}", style = MaterialTheme.typography.titleMedium) }
            item { PrimaryButton("Add expense", onAdd, Modifier.fillMaxWidth()) }
            item { SectionTitle("Settle up") }
            if (debts.isEmpty()) item { Text("Everyone is settled up.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(debts, key = { it.from + it.to }) { d -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${who(d.from)} pays ${who(d.to)} ${rupees(d.paise)}", Modifier.weight(1f))
                TextButton({ onSettle(Settlement(from = d.from, to = d.to, paise = d.paise, at = System.currentTimeMillis(), groupId = group.id)) }) { Text("Record") }
            } }
            item { SectionTitle("Expenses") }
            items(expenses, key = { it.id }) { SplitFeedRow(it, state, onExpense) {} }
            item { TextButton({ deleting = true }) { Text("Delete group", color = MaterialTheme.colorScheme.error) } }
        }
    } }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete ${group.name}?") }, text = { Text("Its expenses stay in your splits and balances, without the group.") },
        confirmButton = { TextButton({ deleting = false; onDelete() }) { Text("Delete") } }, dismissButton = { TextButton({ deleting = false }) { Text("Keep") } })
}

/**
 * Today's payments (or the last [days] days): tap one to split it however you like, then "Split more payments?" until the user is done.
 * [onDone] gets every payment that was offered, so Neko does not ask about them again.
 */
@Composable fun SplitPaymentsFlow(state: NekoState, model: NekoViewModel, days: Int, onDone: (List<String>) -> Unit) {
    val candidates = Splits.candidates(state.transactions, state.splitExpenses, LocalDate.now(Ledger.india), days)
    val offered = remember { mutableStateListOf<String>() }
    LaunchedEffect(candidates.map { it.id }) { candidates.forEach { if (it.id !in offered) offered += it.id } }
    var step by rememberSaveable { mutableStateOf("choose") }; var splitting by rememberSaveable { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { onDone(offered.toList()) }, properties = DialogProperties(usePlatformDefaultWidth = false)) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (step == "more") Column(Modifier.fillMaxSize().padding(NekoTokens.Page), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.TaskAlt, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Split saved.", style = MaterialTheme.typography.titleMedium)
            Text("Do you want to split more payments?", style = MaterialTheme.typography.headlineSmall)
            PrimaryButton("Yes, split more", { step = "choose" }, Modifier.fillMaxWidth(), enabled = candidates.isNotEmpty())
            if (candidates.isEmpty()) Text("Every ${if (days == 1) "payment from today" else "recent payment"} is split.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton({ onDone(offered.toList()) }, Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch)) { Text("No thanks, all done") }
        } else LazyColumn(contentPadding = PaddingValues(NekoTokens.Page), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton({ onDone(offered.toList()) }) { Icon(Icons.Outlined.Close, "Close") }; Text(if (days == 1) "Split today's payments" else "Split a payment", style = MaterialTheme.typography.headlineSmall) } }
            item { Text("Tap a payment to choose who shares it and how.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (candidates.isEmpty()) item { Text("No payments left to split.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(candidates, key = { it.id }) { tx -> Panel(Modifier.fillMaxWidth().clickable { splitting = tx.id }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(tx.merchant, style = MaterialTheme.typography.titleMedium); Text(dateText(tx.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(rupees(tx.amountPaise), style = MaterialTheme.typography.titleMedium)
                }
            } }
        }
    } }
    splitting?.let { id -> state.transactions.find { it.id == id }?.let { tx ->
        SplitEditor(state, model, null, tx, null, onSaved = { step = "more" }) { splitting = null }
    } }
}
