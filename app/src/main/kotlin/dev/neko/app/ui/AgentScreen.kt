package dev.neko.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp

@Composable fun AgentScreen(state:NekoState,model:NekoViewModel,onSettings:()->Unit)=AgentContent(state,model::send,model::goal,model::goalEnabled,model::applyProposal,{model.pause(!state.paused)},model::refresh,onSettings)
@Composable fun AgentContent(state:NekoState,onSend:(String)->Unit,onGoal:(String,Int)->Unit,onGoalEnabled:(String,Boolean)->Unit,onApprove:(Proposal)->Unit,onPause:()->Unit,onRefresh:()->Unit,onSettings:()->Unit){
    var tab by rememberSaveable{mutableStateOf("Activity")};var text by rememberSaveable{mutableStateOf("")};var goalDialog by remember{mutableStateOf(false)};var proposal by remember{mutableStateOf<Proposal?>(null)}
    Column(Modifier.fillMaxSize()){
        Row(Modifier.padding(horizontal=NekoTokens.Page,vertical=16.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Neko",style=MaterialTheme.typography.headlineLarge);Text(if(state.paused)"Taking a pause"else"Here to help you follow through",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};IconButton(onRefresh,enabled=!state.busy){Icon(Icons.Outlined.Refresh,"Refresh agent")};IconButton(onPause,enabled=state.paired){Icon(if(state.paused)Icons.Outlined.PlayArrow else Icons.Outlined.Pause,if(state.paused)"Resume scheduled checks"else"Pause scheduled checks")}}
        Row(Modifier.padding(horizontal=24.dp).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("Chat","Activity","Responsibilities").forEach{name->FilterChip(tab==name,{tab=name},label={Text(name)})}}
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            if(!state.aiEnabled)item{EmptyState("Let's connect","Enable cloud AI to give Neko structured transaction context for questions, categorization, and check-ins. You choose the model and can turn sharing off.",Icons.Outlined.AutoAwesome){PrimaryButton(if(state.paired)"Review AI settings"else"Connect Neko",onSettings)}}
            when(tab){
                "Chat"->{
                    if(state.chat.isEmpty())item{Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
                        NekoCat(Modifier.size(150.dp,165.dp),animate=!state.reducedMotion&&!state.paused)
                        Text("What's on your mind?",style=MaterialTheme.typography.headlineSmall)
                        Text("Ask about spending, review an entry, or give me a responsibility.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }}
                    if(state.aiEnabled&&state.chat.isEmpty())item{Column(verticalArrangement=Arrangement.spacedBy(8.dp)){listOf("Help me review uncategorized transactions","How am I doing against my budgets?","Summarize my recent spending").forEach{question->OutlinedButton({onSend(question)},Modifier.fillMaxWidth(),enabled=!state.busy){Text(question)}}}}
                    itemsIndexed(state.chat){_,message->Row(Modifier.fillMaxWidth(),horizontalArrangement=if(message.first=="user")Arrangement.End else Arrangement.Start){Panel(Modifier.widthIn(max=330.dp),if(message.first=="user")MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface){Text(if(message.first=="user")"YOU"else"NEKO",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary);Text(message.second)}}}
                    if(state.pendingTasks>0)item{StatusPill("${state.pendingTasks} tasks in progress")}
                }
                "Activity"->{
                    if(state.activity.isEmpty())item{EmptyState("A quiet start","Neko's check-ins, category suggestions, and task outcomes will appear here. Every proposed ledger change needs your approval.")}
                    items(state.activity,key={it.id}){item->Panel(Modifier.fillMaxWidth()){
                        Text(dateText(item.createdAt),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(item.title,style=MaterialTheme.typography.titleLarge);Text(item.body)
                        item.proposals.forEach{p->OutlinedButton({proposal=p},enabled=!state.busy){Text("Review ${p.category.label} suggestion")}}
                    }}
                }
                else->{
                    item{SectionTitle("Things I'll watch"){TextButton({goalDialog=true},enabled=state.aiEnabled){Text("Add")}}}
                    item{Text("Daily check-ins use Indian Standard Time. They run on the backend even when this app is closed, using the latest synced context.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    if(state.goals.isEmpty())item{EmptyState("Give me a responsibility","For example: check my food budget every evening and point out transactions that need context.",Icons.Outlined.Schedule){PrimaryButton("Add responsibility",{goalDialog=true},enabled=state.aiEnabled)}}
                    items(state.goals,key={it.id}){goal->Panel(Modifier.fillMaxWidth()){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(goal.title,style=MaterialTheme.typography.titleMedium);Text("Daily · %02d:00 IST".format(goal.hour),color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(goal.enabled,{onGoalEnabled(goal.id,it)},enabled=!state.busy)}}}
                }
            }
            item{Text(if(state.lastSync>0)"Last context sync: ${dateText(state.lastSync)}. Neko can only see shared, synced data."else"Cloud context hasn't been synced yet.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        if(tab=="Chat")Row(Modifier.imePadding().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(text,{text=it},Modifier.weight(1f),placeholder={Text("Ask Neko…")},shape=NekoTokens.ControlShape,maxLines=4,enabled=state.aiEnabled&&!state.paused)
            FilledIconButton({val question=text.trim();if(question.isNotEmpty()){onSend(question);text=""}},Modifier.size(48.dp),enabled=text.isNotBlank()&&state.aiEnabled&&!state.busy&&!state.paused){Icon(Icons.Outlined.ArrowUpward,"Send message")}
        }
    }
    if(goalDialog){var title by remember{mutableStateOf("")};var hour by remember{mutableStateOf("20")};var error by remember{mutableStateOf<String?>(null)}
        AlertDialog(onDismissRequest={goalDialog=false},title={Text("A new responsibility")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){OutlinedTextField(title,{title=it},label={Text("What should Neko watch?")},minLines=2);OutlinedTextField(hour,{hour=it},label={Text("Daily hour in IST (0–23)")});if(error!=null)Text(error!!,color=MaterialTheme.colorScheme.error)}},confirmButton={TextButton({val h=hour.toIntOrNull();if(title.isNotBlank()&&h!=null&&h in 0..23){onGoal(title,h);goalDialog=false}else error="Enter a responsibility and hour from 0 to 23"}){Text("Save responsibility")}},dismissButton={TextButton({goalDialog=false}){Text("Cancel")}})
    }
    proposal?.let{p->val tx=state.transactions.find{it.id==p.transactionId};AlertDialog(onDismissRequest={proposal=null},title={Text("Approve category change?")},text={Text("${tx?.merchant?:"Transaction"}: ${tx?.category?.label?:"Unknown"} → ${p.category.label}\n\n${p.reason}\n\nOnly the category will change after you confirm.")},confirmButton={TextButton({onApprove(p);proposal=null},enabled=tx!=null&&tx.updatedAt==p.expectedUpdatedAt){Text("Confirm change")}},dismissButton={TextButton({proposal=null}){Text("Keep current")}})}
}
