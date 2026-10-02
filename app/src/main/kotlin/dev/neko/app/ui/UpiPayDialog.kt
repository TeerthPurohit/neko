package dev.neko.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.neko.core.Budgeting
import dev.neko.core.UpiPay

/**
 * Pay someone from Neko: the payment is saved first, then Google Pay, PhonePe or any other UPI app opens with the details filled in.
 * Nothing is counted in reports until the UPI app (or your bank's SMS) confirms it.
 */
@Composable fun UpiPayDialog(onDismiss: () -> Unit, onPay: (vpa: String, name: String, amountPaise: Long, note: String) -> Unit) {
    var vpa by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var tried by rememberSaveable { mutableStateOf(false) }
    val paise = Budgeting.parseRupees(amount)
    val vpaOk = UpiPay.isValidVpa(vpa)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pay with UPI") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(vpa, { vpa = it.trim().take(100) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("UPI ID") }, placeholder = { Text("name@bank") },
                    isError = tried && !vpaOk, supportingText = { if (tried && !vpaOk) Text("Enter a valid UPI ID, like name@bank") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(name, { name = it.take(50) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Paying (optional)") })
                OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(14) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Amount in rupees") }, prefix = { Text("₹ ") },
                    isError = tried && paise == null, supportingText = { if (tried && paise == null) Text("Enter an amount like 250 or 99.50") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(note, { note = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Note (optional)") })
                Text("Neko saves this payment now and updates it when it goes through.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton({ tried = true; if (vpaOk && paise != null) onPay(vpa, name, paise, note) }) { Text("Open UPI app") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
