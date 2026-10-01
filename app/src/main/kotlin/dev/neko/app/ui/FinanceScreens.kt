package dev.neko.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.neko.core.*
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun HomeScreen(
    state:NekoState,
    smsGranted:Boolean,
    onSettings:()->Unit,
    onLedger:()->Unit,
    onSend:(String)->Unit,
    onPermissions:()->Unit,
) {
    var prompt by rememberSaveable { mutableStateOf("") }
    val focusManager=LocalFocusManager.current
    val waiting=state.pendingTasks>0
    val accent=MaterialTheme.colorScheme.primary
    val halo=accent.copy(alpha=if(MaterialTheme.colorScheme.background.luminance()<0.25f)0.28f else 0.16f)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start=NekoTokens.Page,end=NekoTokens.Page,top=8.dp,bottom=4.dp),
            verticalAlignment=Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text("Neko",style=MaterialTheme.typography.headlineLarge)
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(7.dp).background(if(state.paused)MaterialTheme.colorScheme.outline else accent,CircleShape))
                    Text(
                        when { state.paused->"Taking a pause";smsGranted->"Listening for bank SMS";else->"Ready when you are" },
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onSettings,modifier=Modifier.size(NekoTokens.Touch)) { Icon(Icons.Outlined.Tune,"Settings") }
        }

        Box(Modifier.fillMaxWidth().height(218.dp),contentAlignment=Alignment.Center) {
            Box(
                Modifier.fillMaxSize(0.92f).graphicsLayer { alpha=0.90f }
                    .background(Brush.radialGradient(listOf(halo,halo.copy(alpha=0.07f),Color.Transparent)),CircleShape),
            )
            NekoMascot3D(
                modifier=Modifier.fillMaxWidth().height(224.dp),
                speaking=waiting||state.busy,
                reducedMotion=state.reducedMotion||state.paused,
            )
        }

        LazyColumn(
            modifier=Modifier.weight(1f).fillMaxWidth(),
            contentPadding=PaddingValues(start=NekoTokens.Page,end=NekoTokens.Page,top=4.dp,bottom=12.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp),
        ) {
            if(state.chat.isEmpty()) {
                item {
                    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("YOUR MONEY, IN GOOD COMPANY",style=MaterialTheme.typography.labelSmall,color=accent)
                        Text(
                            if(state.userName.equals("You",true))"Hi, I'm Neko. What would you like to figure out?"
                            else "Hi ${state.userName}. What would you like to figure out?",
                            style=MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            when {
                                !state.aiEnabled->"Your ledger stays on this phone. Connect your OpenRouter key in Settings to chat about spending, budgets, and transactions."
                                state.transactions.isEmpty()->"I'll keep an eye out for bank notices. Ask me about money whenever you like."
                                else->"I can help you understand a purchase, check a budget, or review something that needs context."
                            },
                            style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if(!smsGranted)item {
                    Surface(
                        onClick=onPermissions,
                        modifier=Modifier.fillMaxWidth().heightIn(min=NekoTokens.Touch),
                        shape=NekoTokens.ControlShape,
                        color=MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Row(Modifier.padding(horizontal=14.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.MarkChatUnread,null,tint=MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text("Let me notice bank payments",style=MaterialTheme.typography.titleSmall)
                                Text("SMS stays on this phone",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Outlined.ArrowForward,"Allow transaction alerts",tint=MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if(state.aiEnabled&&!state.paused)item {
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf("What should I review?","How are my budgets?","Summarize this month").forEach { example ->
                            SuggestionChip(
                                onClick={onSend(example)},
                                label={Text(example)},
                                enabled=!state.busy,
                                shape=NekoTokens.ControlShape,
                            )
                        }
                    }
                }
            } else {
                item { Text("A little more clarity, one conversation at a time.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                itemsIndexed(state.chat) { _,message ->
                    val mine=message.first=="user"
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=if(mine)Arrangement.End else Arrangement.Start) {
                        Surface(
                            modifier=Modifier.widthIn(max=320.dp),
                            shape=RoundedCornerShape(22.dp),
                            color=if(mine)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Column(Modifier.padding(horizontal=15.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                if(!mine)Text("NEKO",style=MaterialTheme.typography.labelSmall,color=accent)
                                Text(message.second,style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
                if(waiting||state.busy)item {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                        CircularProgressIndicator(Modifier.size(15.dp),strokeWidth=2.dp)
                        Text("Neko is thinking…",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if(state.transactions.any{it.review==ReviewStatus.DRAFT})item {
                    TextButton(onClick=onLedger) {
                        Text("I have ${state.transactions.count{it.review==ReviewStatus.DRAFT}} entries to review")
                        Icon(Icons.Outlined.ArrowForward,null,Modifier.padding(start=5.dp).size(16.dp))
                    }
                }
            }
        }

        if(!state.aiEnabled) {
            TextButton(onClick=onSettings,modifier=Modifier.align(Alignment.CenterHorizontally),enabled=!state.busy) {
                Text("Connect my AI key")
                Icon(Icons.Outlined.ArrowForward,null,Modifier.padding(start=5.dp).size(16.dp))
            }
        }

        Row(
            Modifier.fillMaxWidth().imePadding().padding(start=12.dp,end=12.dp,top=5.dp,bottom=8.dp),
            verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value=prompt,
                onValueChange={prompt=it},
                modifier=Modifier.weight(1f),
                placeholder={Text(if(state.aiEnabled)"Ask Neko anything…"else"Connect AI to start chatting")},
                shape=RoundedCornerShape(24.dp),
                maxLines=4,
                enabled=state.aiEnabled&&!state.paused&&!state.busy,
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),
                keyboardActions=KeyboardActions(onSend={
                    val value=prompt.trim()
                    if(value.isNotEmpty()){onSend(value);prompt="";focusManager.clearFocus()}
                }),
            )
            FilledIconButton(
                onClick={val value=prompt.trim();if(value.isNotEmpty()){onSend(value);prompt="";focusManager.clearFocus()}},
                modifier=Modifier.size(NekoTokens.Touch),
                enabled=prompt.isNotBlank()&&state.aiEnabled&&!state.busy&&!state.paused,
            ) { Icon(Icons.Outlined.ArrowUpward,"Send to Neko") }
        }
    }
}

@Composable fun LedgerScreen(state:NekoState,onAdd:()->Unit,onTransaction:(Transaction)->Unit,onExport:()->Unit){
    var query by rememberSaveable{mutableStateOf("")};var filter by rememberSaveable{mutableStateOf("All")}
    val rows=state.transactions.filter { tx->(filter!="Review"||tx.review==ReviewStatus.DRAFT)&&(filter!="Credits"||tx.direction==Direction.CREDIT)&&(filter!="Debits"||tx.direction==Direction.DEBIT)&&(query.isBlank()||listOf(tx.merchant,tx.account,tx.notes,tx.category.label,tx.paymentMethod,Money.decimal(tx.amountPaise)).any{it.contains(query,true)}) }
    LazyColumn(contentPadding=PaddingValues(NekoTokens.Page),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{ScreenTitle("Your ledger","Every entry has a story."){IconButton(onAdd){Icon(Icons.Outlined.Add,"Add transaction")}}}
        item{OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),placeholder={Text("Search merchant, amount, or notes")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,shape=NekoTokens.ControlShape)}
        item{Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("All","Review","Debits","Credits").forEach{name->FilterChip(selected=filter==name,onClick={filter=name},label={Text(name)})}}}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("${rows.size} entries",color=MaterialTheme.colorScheme.onSurfaceVariant);TextButton(onExport,enabled=state.transactions.isNotEmpty()){Icon(Icons.Outlined.FileDownload,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Export CSV")}}}
        if(rows.isEmpty())item{EmptyState(if(query.isBlank())"No entries here yet"else"No matching entries",if(query.isBlank())"Capture a bank notice or add a manual transaction to begin."else"Try another merchant, account, or category.")}
        items(rows,key={it.id}){TransactionRow(it){onTransaction(it)}}
    }
}

@Composable fun InsightsScreen(state:NekoState,model:NekoViewModel){
    var month by rememberSaveable{mutableStateOf(YearMonth.now(Ledger.india).toString())};var budgetDialog by remember{mutableStateOf(false)}
    val chosen=YearMonth.parse(month);val report=Ledger.report(state.transactions,chosen)
    LazyColumn(contentPadding=PaddingValues(NekoTokens.Page),verticalArrangement=Arrangement.spacedBy(24.dp)){
        item{ScreenTitle("A clearer picture","Your spending, with context.")}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){IconButton({month=chosen.minusMonths(1).toString()}){Icon(Icons.Outlined.ChevronLeft,"Previous month")};Text(chosen.format(DateTimeFormatter.ofPattern("MMMM yyyy",Locale.ENGLISH)),style=MaterialTheme.typography.titleMedium);IconButton({month=chosen.plusMonths(1).toString()},enabled=chosen<YearMonth.now(Ledger.india)){Icon(Icons.Outlined.ChevronRight,"Next month")}}}
        item{Panel(Modifier.fillMaxWidth()){
            Text("NET SPENDING",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(rupees(report.netSpending),style=MaterialTheme.typography.displaySmall)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Gross ${rupees(report.spending)}");Text("Refunds ${rupees(report.refunds)}",color=MaterialTheme.colorScheme.primary)}
            Text("Income ${rupees(report.income)}",color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(report.reimbursements>0)Text("Friend reimbursements ${rupees(report.reimbursements)}",color=MaterialTheme.colorScheme.primary)
            if(report.funding>0)Text("Pocket money / funding ${rupees(report.funding)} · excluded from income",color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(report.investmentGains>0)Text("Investment gains ${rupees(report.investmentGains)} · income",color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(report.drafts>0)Text("${report.drafts} drafts excluded until you confirm them.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
        item{SectionTitle("Where it went")}
        if(report.categories.isEmpty())item{EmptyState("A fresh month","Confirmed spending will appear here. Transfers between your own accounts are excluded.",Icons.Outlined.DonutLarge)}
        items(report.categories.entries.sortedByDescending{it.value},key={it.key.name}){entry->Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(entry.key.label,style=MaterialTheme.typography.titleMedium);Text(rupees(entry.value),fontWeight=FontWeight.Medium)}
            LinearProgressIndicator(progress={if(report.spending>0)entry.value.toFloat()/report.spending else 0f},modifier=Modifier.fillMaxWidth().height(7.dp),color=MaterialTheme.colorScheme.primary,trackColor=MaterialTheme.colorScheme.surfaceVariant)
        }}
        item{SectionTitle("Monthly budgets"){TextButton({budgetDialog=true}){Icon(Icons.Outlined.Add,null,Modifier.size(18.dp));Text("Set budget")}}}
        if(state.budgets.isEmpty())item{Text("Give a category a little breathing room. Set a monthly limit and Neko can help you watch it.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(state.budgets,key={it.category.name}){budget->val used=report.categories[budget.category]?:0;Panel(Modifier.fillMaxWidth()){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(budget.category.label,style=MaterialTheme.typography.titleMedium);Text(if(used>budget.amountPaise)"Over budget"else"${((used.toDouble()/budget.amountPaise)*100).toInt()}% used",color=if(used>budget.amountPaise)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)}
            LinearProgressIndicator(progress={minOf(1f,used.toFloat()/budget.amountPaise)},modifier=Modifier.fillMaxWidth().height(8.dp),color=if(used>budget.amountPaise)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text("${rupees(used)} of ${rupees(budget.amountPaise)}",color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
    }
    if(budgetDialog)BudgetDialog(onDismiss={budgetDialog=false},onSave={category,amount->model.budget(category,amount);budgetDialog=false})
}
