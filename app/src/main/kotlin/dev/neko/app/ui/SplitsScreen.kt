package dev.neko.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.neko.core.Money
import org.json.JSONObject

@Composable fun SplitsScreen(state:NekoState,model:NekoViewModel,onSettings:()->Unit){
    var creating by remember{mutableStateOf(false)};var joining by remember{mutableStateOf(false)};var group by remember{mutableStateOf<GroupItem?>(null)}
    LazyColumn(contentPadding=PaddingValues(NekoTokens.Page),verticalArrangement=Arrangement.spacedBy(20.dp)){
        item{ScreenTitle("Good company","Shared bills, clear boundaries."){IconButton(model::refresh,enabled=!state.busy){Icon(Icons.Outlined.Refresh,"Refresh groups")}}}
        item{Panel(Modifier.fillMaxWidth(),MaterialTheme.colorScheme.primaryContainer){Text("Your groups, together",style=MaterialTheme.typography.headlineSmall);Text("Use your existing Splitwise groups or start a Neko group. You review every shared expense before it is posted.",color=MaterialTheme.colorScheme.onPrimaryContainer)}}
        if(!state.splitwiseConnected)item{OutlinedButton(onSettings,Modifier.fillMaxWidth()){Icon(Icons.Outlined.Link,null);Spacer(Modifier.width(8.dp));Text("Connect your Splitwise")}}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){OutlinedButton({creating=true},Modifier.weight(1f),enabled=state.paired){Text("New group")};OutlinedButton({joining=true},Modifier.weight(1f),enabled=state.paired){Text("Join Neko group")}}}
        item{SectionTitle("Friend groups")}
        if(state.groups.isEmpty())item{EmptyState("Start with your people","Connect Splitwise to import existing groups, or connect your Neko backend to create and join groups.",Icons.Outlined.Group)}
        items(state.groups,key={it.source+it.id}){g->Panel(Modifier.fillMaxWidth()){
            StatusPill(g.source);Text(g.name,style=MaterialTheme.typography.titleLarge)
            if(g.members>0)Text("${g.members} members",color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){PrimaryButton("Split a bill",{group=g},enabled=!state.busy);if(g.source=="Neko")TextButton({model.action{val data=modelGroupDetails(model,g);model.note(data)}},enabled=!state.busy){Text("Balances")}}
        }}
        item{SectionTitle("Recent Splitwise expenses")}
        items(state.expenses.take(30)){expense->Panel(Modifier.fillMaxWidth()){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(expense.title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);Text("${expense.currency} ${expense.cost}")}}}
        item{Text("Imported Splitwise expenses stay separate from your bank ledger so they don't double-count spending. This preview shows the latest 100 imported expenses. Neko groups use equal INR splits with exact paise rounding.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
    if(creating||joining){var value by remember{mutableStateOf("")};AlertDialog(onDismissRequest={creating=false;joining=false},title={Text(if(joining)"Join a Neko group"else"Create a Neko group")},text={OutlinedTextField(value,{value=it},label={Text(if(joining)"Invite code"else"Group name")})},confirmButton={TextButton({if(joining)model.joinGroup(value.trim())else model.createGroup(value.trim());creating=false;joining=false},enabled=value.isNotBlank()){Text(if(joining)"Join"else"Create")}},dismissButton={TextButton({creating=false;joining=false}){Text("Cancel")}})}
    group?.let{g->var title by remember{mutableStateOf("")};var amount by remember{mutableStateOf("")};var review by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)};val requestId=remember{java.util.UUID.randomUUID().toString()}
        AlertDialog(onDismissRequest={group=null},title={Text(if(review)"Confirm shared expense"else"Split a bill")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("${g.name} · ${g.source}")
            if(review){Text("Post INR $amount for “$title”? You are the payer. The bill will be split equally across the group's members.");Text("This changes the shared group. Neko will post it only after you confirm.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
            else{OutlinedTextField(title,{title=it},label={Text("What was it for?")});OutlinedTextField(amount,{amount=it},label={Text("Total amount in INR")})}
            if(error!=null)Text(error!!,color=MaterialTheme.colorScheme.error)
        }},confirmButton={TextButton({try{require(title.isNotBlank());val paise=Money.parse(amount);if(review){model.split(g,paise,title,requestId);group=null}else review=true}catch(_:Exception){error="Enter a description and a positive INR amount"}}){Text(if(review)"Confirm & post"else"Review split")}},dismissButton={TextButton({if(review)review=false else group=null}){Text(if(review)"Edit"else"Cancel")}})
    }
}
private suspend fun modelGroupDetails(model:NekoViewModel,group:GroupItem):String=model.groupDetails(group)
