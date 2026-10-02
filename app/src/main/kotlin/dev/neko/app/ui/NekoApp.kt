package dev.neko.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.neko.core.Transaction

@Composable fun NekoApp(state:NekoState,model:NekoViewModel,requestedTransaction:String?,requestedAgent:Boolean,onIntentConsumed:()->Unit){
    var page by rememberSaveable{mutableStateOf("Home")};var selectedId by rememberSaveable{mutableStateOf<String?>(null)};var adding by rememberSaveable{mutableStateOf(false)}
    val context=LocalContext.current
    var smsGranted by remember{mutableStateOf(context.checkSelfPermission(Manifest.permission.RECEIVE_SMS)==PackageManager.PERMISSION_GRANTED)}
    var inboxGranted by remember{mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED)}
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->
        smsGranted=result[Manifest.permission.RECEIVE_SMS]?:smsGranted
        if(result[Manifest.permission.READ_SMS]==true){inboxGranted=true;model.scanInbox()}
    }
    var paying by rememberSaveable{mutableStateOf(false)}
    // Splitting: [splitDays] opens the split flow (1 = today's payments). Each time the app comes to the front, Neko asks once about today's
    // payments it has not asked about yet.
    var splitDays by rememberSaveable{mutableStateOf(0)};var splitPrompt by rememberSaveable{mutableStateOf("")};var opened by remember{mutableStateOf(0)}
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START){opened++}
    LaunchedEffect(opened,state.loading){
        if(!state.loading&&splitDays==0)splitPrompt=dev.neko.core.Splits.candidates(state.transactions,state.splitExpenses,java.time.LocalDate.now(dev.neko.core.Ledger.india)).filter{it.id !in state.splitAsked}.joinToString("\n"){it.id}
    }
    // Saved with the dialog (rotation, process death) so a scanned merchant QR keeps its fields.
    val parsedSaver=androidx.compose.runtime.saveable.listSaver<dev.neko.core.UpiPay.Parsed?,String>(
        save={p->if(p==null)emptyList() else listOf(p.vpa,p.name,p.amountPaise?.toString().orEmpty(),p.note,p.extras.entries.joinToString("\n"){"${it.key}\t${it.value}"},p.raw)},
        restore={l->if(l.size<5)null else dev.neko.core.UpiPay.Parsed(l[0],l[1],l[2].toLongOrNull(),l[3],l[4].lines().filter{it.isNotBlank()}.associate{line->val kv=line.split('\t',limit=2);kv[0] to kv.getOrElse(1){""}},l.getOrElse(5){""})})
    var payPrefill by rememberSaveable(stateSaver=parsedSaver){mutableStateOf<dev.neko.core.UpiPay.Parsed?>(null)}
    // Opens Google's built-in QR scanner (no camera permission needed). [onResult] gets the payment from a UPI QR code, or null if the user cancelled or scanning is unavailable.
    val scanQr:((dev.neko.core.UpiPay.Parsed?)->Unit)->Unit={onResult->
        try {
            val options=com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder().setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context,options).startScan()
                .addOnSuccessListener{code->val parsed=dev.neko.core.UpiPay.parseLink(code.rawValue.orEmpty());if(parsed==null)model.note("That QR code is not a UPI payment code.");onResult(parsed)}
                .addOnCanceledListener{onResult(null)}
                .addOnFailureListener{model.note("The QR scanner is not available on this phone. Enter the UPI ID by hand.");onResult(null)}
        } catch(_:Exception){model.note("The QR scanner is not available on this phone. Enter the UPI ID by hand.");onResult(null)}
    }
    var statementFile by rememberSaveable{mutableStateOf<String?>(null)}
    val statementPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)statementFile=uri.toString()}
    // File managers label PDFs and CSV exports inconsistently (application/pdf, application/x-pdf, octet-stream, ...) and grey out files whose label is not
    // listed, so every file is selectable and the content is checked after it is picked.
    val pickStatement={statementPicker.launch(arrayOf("*/*"))}
    val upi=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        val data=result.data
        // UPI apps return either one "response" string or separate extras; both carry Status and ApprovalRefNo.
        model.finishUpi(data?.getStringExtra("response")?:data?.let{d->listOf("Status","ApprovalRefNo").mapNotNull{key->d.getStringExtra(key)?.let{"$key=$it"}}.joinToString("&")})
    }
    val smsPermissions={permissions.launch(if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.RECEIVE_SMS,Manifest.permission.READ_SMS,Manifest.permission.POST_NOTIFICATIONS)else arrayOf(Manifest.permission.RECEIVE_SMS,Manifest.permission.READ_SMS))}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->if(uri!=null)model.action { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(dev.neko.core.Ledger.csv(state.transactions))}};model.note("Ledger exported.") }}
    val firebase=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){try{val config=context.contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()}?:error("Could not read file");model.firebase(config)}catch(_:Exception){model.note("Could not read Firebase configuration.")}}}
    LaunchedEffect(requestedTransaction,requestedAgent,state.loading){
        if(!state.loading){if(requestedTransaction!=null){page="Ledger";selectedId=requestedTransaction;onIntentConsumed()}else if(requestedAgent){page="Activity";onIntentConsumed()}}
    }
    val selected=state.transactions.find{it.id==selectedId}
    Scaffold(containerColor=MaterialTheme.colorScheme.background,bottomBar={
        if(page!="Settings")Surface(shape=RoundedCornerShape(32.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.navigationBarsPadding().padding(horizontal=16.dp,vertical=8.dp)){
            NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp){
                listOf("Home" to ("Neko" to Icons.Outlined.AutoAwesome),"Ledger" to ("Ledger" to Icons.Outlined.ReceiptLong),"Activity" to ("Activity" to Icons.Outlined.Schedule),"Insights" to ("Insights" to Icons.Outlined.DonutLarge),"Splits" to ("Splits" to Icons.Outlined.Group)).forEach{(route,item)->val(name,icon)=item;NavigationBarItem(selected=page==route,onClick={page=route},icon={Icon(icon,name)},label={Text(name)},colors=NavigationBarItemDefaults.colors(indicatorColor=MaterialTheme.colorScheme.primaryContainer))}
            }
        }
    }){padding->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)){
            if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.primary)
            if(state.loading)Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.Center){CircularProgressIndicator()}
            else when(page){
                "Home"->HomeScreen(state,smsGranted,onSettings={page="Settings"},onLedger={page="Ledger"},onSend=model::send,onPermissions=smsPermissions,onSaveBudgetPlan=model::saveBudgetPlan,onPay={scanQr{scanned->payPrefill=scanned;paying=true}},onImportStatement=pickStatement)
                "Ledger"->LedgerScreen(state,onAdd={adding=true},onTransaction={selectedId=it.id},onExport={export.launch("neko-ledger.csv")})
                "Activity"->AgentScreen(state,model,onSettings={page="Settings"})
                "Insights"->InsightsScreen(state,model)
                "Splits"->SplitsScreen(state,model,onSplitPayments={splitDays=60})
                "Settings"->SettingsScreen(state,model,smsGranted,inboxGranted,onBack={page="Home"},onPermissions=smsPermissions,onImportStatement=pickStatement,onFirebase={firebase.launch(arrayOf("application/json","text/plain"))})
            }
        }
    }
    if(paying)UpiPayDialog(payPrefill,onScan={scanQr{scanned->if(scanned!=null)payPrefill=scanned}},onDismiss={paying=false},onPay={vpa,name,paise,note,extras,scanned->
        paying=false
        model.beginUpi(vpa,name,paise,note,extras,scanned){link->
            try{
                if(link!=null)upi.launch(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(link)),"Pay with"))
                else {
                    // Payments to a person: copy the UPI ID and open the user's UPI app, where they pay as usual. The bank SMS settles the record.
                    context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("UPI ID",vpa))
                    val pm=context.packageManager
                    val apps=pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("upi://pay")),0)
                        .mapNotNull{pm.getLaunchIntentForPackage(it.activityInfo.packageName)}.distinctBy{it.`package`}
                    if(apps.isEmpty())model.note("No UPI app was found on this phone.")
                    else {
                        model.note("Copied $vpa. Paste it in your UPI app and pay ${rupees(paise)}.")
                        upi.launch(if(apps.size==1)apps.single() else android.content.Intent.createChooser(apps.first(),"Pay ${rupees(paise)} with").putExtra(android.content.Intent.EXTRA_INITIAL_INTENTS,apps.drop(1).toTypedArray()))
                    }
                }
            } catch(_:Exception){model.note("No UPI app was found on this phone.")}
        }
    })
    statementFile?.let{file->StatementImportDialog(state.busy,onDismiss={statementFile=null},onImport={account,password,thisMonthOnly->
        model.importStatement(android.net.Uri.parse(file),account,password,thisMonthOnly){statementFile=null}
    })}
    state.statementResult?.let{result->StatementResultDialog(result,onConfirmSpending=model::confirmStatementSpending,onDismiss=model::dismissStatementResult)}
    val askAbout=splitPrompt.split('\n').mapNotNull{id->state.transactions.find{it.id==id}}
    if(askAbout.isNotEmpty()&&splitDays==0&&!paying&&state.upiPromptId==null)AlertDialog(onDismissRequest={},title={Text("Any payments to split?")},
        text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text("You made ${if(askAbout.size==1)"a payment" else "${askAbout.size} payments"} today. Do any of them need splitting?")
            askAbout.take(4).forEach{Text("${rupees(it.amountPaise)} · ${it.merchant}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(askAbout.size>4)Text("and ${askAbout.size-4} more",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }},
        confirmButton={TextButton({splitPrompt="";splitDays=1}){Text("Yes, split")}},
        dismissButton={TextButton({model.markSplitAsked(askAbout.map{it.id});splitPrompt=""}){Text("No, all mine")}})
    if(splitDays>0)SplitPaymentsFlow(state,model,splitDays){offered->model.markSplitAsked(offered);splitDays=0}
    val unsettled=state.upiPromptId?.let{id->state.transactions.find{it.id==id&&it.status==dev.neko.core.PaymentStatus.PENDING}}
    if(unsettled!=null)AlertDialog(onDismissRequest={model.resolveUpi(null)},title={Text("Did the payment go through?")},
        text={Text("${rupees(unsettled.amountPaise)} to ${unsettled.merchant}. If you're not sure, Neko will settle it when your bank's SMS arrives.")},
        confirmButton={TextButton({model.resolveUpi(true)}){Text("Yes, it was paid")}},
        dismissButton={Row{TextButton({model.resolveUpi(false)}){Text("No, it failed")};TextButton({model.resolveUpi(null)}){Text("Not sure")}}})
    if(adding)TransactionDialog(null,state,onDismiss={adding=false},onSave={tx,category,merchant,notes,amount,date,status,treatment,relatedId,principalPaise->model.save(tx.copy(category=category,merchant=merchant,notes=notes,amountPaise=amount,occurredAt=date,status=status,spendingTreatment=treatment,relatedTransactionId=relatedId,principalPaise=principalPaise));adding=false})
    if(selected!=null)TransactionDialog(selected,state,onDismiss={selectedId=null},onSave={tx,category,merchant,notes,amount,date,status,treatment,relatedId,principalPaise->model.correct(tx,category,merchant,notes,amount,date,status,treatment,relatedId,principalPaise);selectedId=null},onMatch={other->model.match(selected.id,other.id);selectedId=null},onUnmatch={model.unmatch(selected.id);selectedId=null},onOwnTransfer={model.ownTransfer(selected.id);selectedId=null})
    if(state.error!=null||state.message!=null)AlertDialog(onDismissRequest=model::dismiss,title={Text(if(state.error!=null)"Needs attention"else"Neko")},text={androidx.compose.foundation.text.selection.SelectionContainer{Text(state.error?:state.message.orEmpty())}},confirmButton={TextButton(onClick=model::dismiss){Text("Got it")}})
}
