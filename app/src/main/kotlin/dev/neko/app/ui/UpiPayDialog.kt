package dev.neko.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.neko.core.Budgeting
import dev.neko.core.Money
import dev.neko.core.UpiPay

/**
 * Merchant fields from a scanned QR code are kept only while the UPI ID is the scanned one. The `sign` field is a signature over the scanned amount,
 * name and note, so it is dropped as soon as any of those is changed.
 */
internal fun scannedFields(prefill: UpiPay.Parsed?, vpa: String, name: String, paise: Long?, note: String): Map<String, String> {
    if (prefill == null || vpa != prefill.vpa) return emptyMap()
    val edited = name != prefill.name || note != prefill.note || (prefill.amountPaise != null && paise != prefill.amountPaise)
    return if (edited) prefill.extras - "sign" else prefill.extras
}

/**
 * Pay someone from Neko: scan their UPI QR code (or type the details), the payment is saved first, then Google Pay, PhonePe or any other
 * UPI app opens with the details filled in. Nothing is counted in reports until the UPI app (or your bank's SMS) confirms it.
 * [prefill] comes from a scanned QR code; its merchant fields are kept only while the UPI ID is unchanged.
 */
@Composable fun UpiPayDialog(prefill: UpiPay.Parsed?, onScan: () -> Unit, onDismiss: () -> Unit, onPay: (vpa: String, name: String, amountPaise: Long, note: String, extras: Map<String, String>) -> Unit) {
    var vpa by rememberSaveable(prefill) { mutableStateOf(prefill?.vpa.orEmpty()) }
    var name by rememberSaveable(prefill) { mutableStateOf(prefill?.name.orEmpty()) }
    var amount by rememberSaveable(prefill) { mutableStateOf(prefill?.amountPaise?.let { if (it % 100 == 0L) (it / 100).toString() else Money.decimal(it) }.orEmpty()) }
    var note by rememberSaveable(prefill) { mutableStateOf(prefill?.note.orEmpty()) }
    var tried by rememberSaveable { mutableStateOf(false) }
    val paise = Budgeting.parseRupees(amount)
    val vpaOk = UpiPay.isValidVpa(vpa)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pay with UPI") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onScan, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.QrCodeScanner, null); Text("  Scan a UPI QR code") }
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
        confirmButton = { TextButton({ tried = true; if (vpaOk && paise != null) onPay(vpa, name, paise, note, scannedFields(prefill, vpa, name, paise, note)) }) { Text("Open UPI app") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
