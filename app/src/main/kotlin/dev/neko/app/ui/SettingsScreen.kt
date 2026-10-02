package dev.neko.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri

@Composable fun SettingsScreen(state:NekoState,model:NekoViewModel,smsGranted:Boolean,inboxGranted:Boolean,onBack:()->Unit,onPermissions:()->Unit,onFirebase:()->Unit){
    val context=LocalContext.current
    var pairing by remember{mutableStateOf(false)};var splitwise by remember{mutableStateOf(false)};var consent by remember{mutableStateOf(false)};var erase by remember{mutableStateOf(false)};var modelKey by remember{mutableStateOf(false)}
    LazyColumn(contentPadding=PaddingValues(NekoTokens.Page),verticalArrangement=Arrangement.spacedBy(24.dp)){
        item{Row(verticalAlignment=Alignment.CenterVertically){IconButton(onBack){Icon(Icons.Outlined.ArrowBack,"Back")};Text("Make Neko yours",style=MaterialTheme.typography.headlineSmall)}}
        item{Panel(Modifier.fillMaxWidth()){
            Text("Appearance",style=MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("system","light","dark").forEach{mode->FilterChip(state.theme==mode,{model.theme(mode)},label={Text(mode.replaceFirstChar{it.uppercase()})})}}
            Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Reduce motion");Text("Keep the cat still",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(state.reducedMotion,model::motion)}
        }}
        item{Panel(Modifier.fillMaxWidth()){
            Text("Transaction capture",style=MaterialTheme.typography.titleLarge);StatusPill(if(smsGranted)"SMS permission granted"else"Permission needed",smsGranted)
            Text("ICICI, IDFC FIRST, AU, HDFC, SBI, Axis, and Kotak bank notices are parsed on your phone. Uncertain entries stay as drafts. Neko never asks for your bank login, PIN, or OTP.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onPermissions){Text("Manage capture permissions")}
            if(inboxGranted)OutlinedButton({model.scanInbox()},enabled=!state.busy){Text("Scan last 90 days of SMS")}
            else Text("Allow SMS access to also import bank notices that arrived before Neko was set up.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            val context=androidx.compose.ui.platform.LocalContext.current
            val keepAlive=remember{ context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
            OutlinedButton({
                runCatching{context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,android.net.Uri.parse("package:"+context.packageName)))}
                    .onFailure{runCatching{context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))}}
            }){Text(if(keepAlive)"Always-on: battery unrestricted" else "Keep Neko always on (allow background)")}
            Text("Neko notes down bank SMS even when the app is closed, and re-checks your inbox every 15 minutes in case Android delayed a message. Phones that restrict background apps can still pause this, so allow background use above. Force-stopping the app pauses it until you open Neko again.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
        item{Panel(Modifier.fillMaxWidth()){
            Text("Agent connection",style=MaterialTheme.typography.titleLarge);StatusPill(if(state.paired)"Backend connected"else"Connect when ready",state.paired)
            if(state.paired)Text(state.backendUrl,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            PrimaryButton(if(state.paired)"Reconnect backend"else"Connect backend",{pairing=true},enabled=!state.busy)
            if(state.paired){OutlinedButton({modelKey=true},enabled=!state.busy){Text(if(state.hasModelKey)"Change OpenRouter key"else"Add OpenRouter key")};if(state.hasModelKey)TextButton(model::removeByok,enabled=!state.busy){Text("Remove OpenRouter key")};TextButton(model::logout,enabled=!state.busy){Text("Log out")}}
            Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Cloud AI");Text("OpenRouter · your chosen model",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(state.aiEnabled,{if(it)consent=true else model.ai(false)},enabled=state.paired&&!state.busy)}
            if(state.paired){var expanded by remember{mutableStateOf(false)};Box{OutlinedButton({expanded=true},Modifier.fillMaxWidth()){Text(state.model)};DropdownMenu(expanded,{expanded=false}){state.models.forEach{m->DropdownMenuItem(text={Text(m)},onClick={model.model(m);expanded=false})}}}}
            Text("This month: ${state.usageRequests} AI calls · $%.4f recorded usage".format(state.usageCost),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Calls and output length are limited on the backend. Neko uses paid models. Set an OpenRouter key spending limit for a firm provider-side cap.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(state.paired)TextButton({erase=true}){Text("Delete shared cloud context",color=MaterialTheme.colorScheme.error)}
        }}
        item{Panel(Modifier.fillMaxWidth()){
            Text("Splitwise",style=MaterialTheme.typography.titleLarge);Text("Connect your existing account directly from this phone. Its key stays encrypted on this device; it isn't sent to Neko's backend.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            PrimaryButton(if(state.splitwiseConnected)"Change connection"else"Connect Splitwise",{splitwise=true})
            if(state.splitwiseConnected)TextButton(model::disconnectSplitwise){Text("Disconnect Splitwise")}
        }}
        if(state.transactions.isNotEmpty())item{Panel(Modifier.fillMaxWidth()){
            Text("Your own accounts",style=MaterialTheme.typography.titleLarge);Text("Mark accounts you own. Neko can suggest matching transfers with the same bank reference; you confirm the pair.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            state.transactions.map{it.account}.distinct().forEach{account->Row(verticalAlignment=Alignment.CenterVertically){Text(account,Modifier.weight(1f));Checkbox(account in state.ownAccounts,{model.setOwned(account,it)})}}
        }}
        item{Panel(Modifier.fillMaxWidth()){
            Text("Push notifications",style=MaterialTheme.typography.titleLarge)
            if(state.firebaseConfigured){
                StatusPill("Firebase configured",true)
                Text("Allow notifications and keep your Neko account connected to receive check-ins. Background sync also retrieves updates when Android permits.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onPermissions){Text("Manage notification permissions")}
            }else{
                Text("Import your Android Firebase configuration to receive server check-ins. Without it, background sync retrieves updates when Android permits.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onFirebase){Text("Import Firebase configuration")}
            }
        }}
        item{Panel(Modifier.fillMaxWidth()){
            Text("Account Aggregator",style=MaterialTheme.typography.titleLarge);StatusPill("Integration pending",false)
            Text("ICICI, IDFC FIRST, and AU are listed as live banks in the AA ecosystem. Neko needs an eligible provider integration before it can connect. Data refresh depends on consent, provider schedules, and bank responses; it is not an instant transaction trigger.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
        item{Text("Neko 0.1 · Personal preview\nYour ledger is local. CSV export is available from the Ledger screen.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
    if(pairing){var url by remember{mutableStateOf(state.backendUrl)};var secret by remember{mutableStateOf("")};var name by remember{mutableStateOf(if(state.userName=="You")""else state.userName)}
        AlertDialog(onDismissRequest={pairing=false},title={Text("Connect your agent")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text("Use your deployed HTTPS backend and its pairing secret. OpenRouter keys belong on the backend.");OutlinedTextField(url,{url=it},label={Text("Backend HTTPS URL")},singleLine=true);OutlinedTextField(name,{name=it},label={Text("Your name")},singleLine=true);OutlinedTextField(secret,{secret=it},label={Text("Pairing secret")},visualTransformation=PasswordVisualTransformation(),singleLine=true)}},confirmButton={TextButton({model.pair(url.trim(),secret,name.trim());pairing=false},enabled=url.startsWith("https://")&&secret.length>=32&&name.isNotBlank()){Text("Connect")}},dismissButton={TextButton({pairing=false}){Text("Cancel")}})
    }
    if(modelKey){var key by remember{mutableStateOf("")};AlertDialog(onDismissRequest={key="";modelKey=false},title={Text("Your OpenRouter key")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text("The key is sent over HTTPS and encrypted on your backend. It isn't saved on this phone or included in the APK. Connecting enables structured cloud context sharing as described below.");OutlinedTextField(key,{key=it},label={Text("OpenRouter API key")},visualTransformation=PasswordVisualTransformation(),singleLine=true)}},confirmButton={TextButton({val submitted=key;key="";modelKey=false;model.byok(submitted)},enabled=key.startsWith("sk-or-")&&!state.busy){Text("Connect key")}},dismissButton={TextButton({key="";modelKey=false}){Text("Cancel")}})}
    if(splitwise){var key by remember{mutableStateOf("")};AlertDialog(onDismissRequest={key="";splitwise=false},title={Text("Connect Splitwise locally")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text("Sign in at Splitwise's app registration page, register an app if allowed, then generate a personal API key on its details page. Splitwise may require Pro for API access. Neko checks your key before saving it, encrypted on this phone.");OutlinedButton({context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://secure.splitwise.com/apps")))}){Text("Open Splitwise API setup")};OutlinedTextField(key,{key=it},label={Text("Your Splitwise API key")},visualTransformation=PasswordVisualTransformation(),singleLine=true)}},confirmButton={TextButton({val submitted=key.trim();key="";model.splitwise(submitted);splitwise=false},enabled=key.isNotBlank()&&!state.busy){Text("Connect")}},dismissButton={TextButton({key="";splitwise=false}){Text("Cancel")}})}
    if(consent)AlertDialog(onDismissRequest={consent=false},title={Text("Share context with Neko?")},text={Text("Neko will upload the latest 150 structured entries: amounts, times, redacted merchant names, categories, bank aliases, and review/payment status, plus budgets. Relevant portions go to OpenRouter and the selected model provider. Your questions are also sent. Raw SMS, account numbers, references, local notes, bank credentials, and your Splitwise key stay on this device. AI drafts changes for your confirmation. You can disable AI or delete shared context anytime.")},confirmButton={TextButton({model.ai(true);consent=false}){Text("Enable cloud AI")}},dismissButton={TextButton({consent=false}){Text("Keep local")}})
    if(erase)AlertDialog(onDismissRequest={erase=false},title={Text("Delete cloud context?")},text={Text("Delete Neko's shared transaction context, chat, tasks, and responsibilities from this backend and disable cloud AI. Your local ledger remains. Provider retention follows its own policy; this cannot recall data already sent to a model.")},confirmButton={TextButton({model.eraseCloud();erase=false}){Text("Delete cloud context")}},dismissButton={TextButton({erase=false}){Text("Cancel")}})
}
