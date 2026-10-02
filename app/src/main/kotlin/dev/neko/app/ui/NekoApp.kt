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
                "Home"->HomeScreen(state,smsGranted,onSettings={page="Settings"},onLedger={page="Ledger"},onSend=model::send,onPermissions=smsPermissions)
                "Ledger"->LedgerScreen(state,onAdd={adding=true},onTransaction={selectedId=it.id},onExport={export.launch("neko-ledger.csv")})
                "Activity"->AgentScreen(state,model,onSettings={page="Settings"})
                "Insights"->InsightsScreen(state,model)
                "Splits"->SplitsScreen(state,model,onSettings={page="Settings"})
                "Settings"->SettingsScreen(state,model,smsGranted,inboxGranted,onBack={page="Home"},onPermissions=smsPermissions,onFirebase={firebase.launch(arrayOf("application/json","text/plain"))})
            }
        }
    }
    if(adding)TransactionDialog(null,state,onDismiss={adding=false},onSave={tx,category,merchant,notes,amount,date,status,treatment,relatedId,principalPaise->model.save(tx.copy(category=category,merchant=merchant,notes=notes,amountPaise=amount,occurredAt=date,status=status,spendingTreatment=treatment,relatedTransactionId=relatedId,principalPaise=principalPaise));adding=false})
    if(selected!=null)TransactionDialog(selected,state,onDismiss={selectedId=null},onSave={tx,category,merchant,notes,amount,date,status,treatment,relatedId,principalPaise->model.correct(tx,category,merchant,notes,amount,date,status,treatment,relatedId,principalPaise);selectedId=null},onMatch={other->model.match(selected.id,other.id);selectedId=null},onUnmatch={model.unmatch(selected.id);selectedId=null})
    if(state.error!=null||state.message!=null)AlertDialog(onDismissRequest=model::dismiss,title={Text(if(state.error!=null)"Needs attention"else"Neko")},text={androidx.compose.foundation.text.selection.SelectionContainer{Text(state.error?:state.message.orEmpty())}},confirmButton={TextButton(onClick=model::dismiss){Text("Got it")}})
}
