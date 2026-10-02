package dev.neko.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.neko.app.data.ImportResult

/** Choose how to read a bank statement file the user picked. Everything is read on this phone; the file is never uploaded. */
@Composable fun StatementImportDialog(busy: Boolean, onDismiss: () -> Unit, onImport: (account: String, password: String, thisMonthOnly: Boolean) -> Unit) {
    var account by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") } // deliberately not saved: a password must not be written into saved instance state
    var thisMonthOnly by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Import a bank statement") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("CSV, Excel (.xls or .xlsx) or PDF from your bank. Neko reads it on this phone, skips payments it already noted from SMS, and adds the rest as drafts.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(account, { account = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Account name (optional)") }, placeholder = { Text("ICICI savings 1234") })
                OutlinedTextField(password, { password = it.take(64) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("PDF password (if it has one)") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("This month only"); Text("Skip older rows in the file", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Switch(thisMonthOnly, { thisMonthOnly = it })
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton({ onImport(account, password, thisMonthOnly) }, enabled = !busy) { Text(if (busy) "Reading..." else "Import") } },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** What the import did, with a one-tap way to confirm the new spending (money received is always left for the user). */
@Composable fun StatementResultDialog(result: ImportResult, onConfirmSpending: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (result.imported > 0) "Statement imported" else "Nothing new to add") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${result.imported} new transaction${if (result.imported == 1) "" else "s"} added as drafts.")
                if (result.alreadyRecorded > 0) Text("${result.alreadyRecorded} matched payments Neko had already noted, so they were not added twice.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (result.alreadyImported > 0) Text("${result.alreadyImported} were already imported from this statement before.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (result.skipped > 0) Text("${result.skipped} line${if (result.skipped == 1) "" else "s"} could not be read and ${if (result.skipped == 1) "was" else "were"} left out.", color = MaterialTheme.colorScheme.error)
                if (result.transfersLinked > 0) Text("${result.transfersLinked} looked like transfers between your own accounts and were linked, so they are not counted as spending.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (result.needsReview > 0) Text("${result.needsReview} look like transfers, card bills or investments, so they stay as drafts for you to check.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (result.newDebitIds.isNotEmpty()) Text("Confirm the ${result.newDebitIds.size} spending entries now? Money received stays a draft for you to check.", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { if (result.newDebitIds.isNotEmpty()) TextButton(onConfirmSpending) { Text("Confirm ${result.newDebitIds.size} spending") } else TextButton(onDismiss) { Text("Got it") } },
        dismissButton = { if (result.newDebitIds.isNotEmpty()) TextButton(onDismiss) { Text("I'll review them") } },
    )
}
