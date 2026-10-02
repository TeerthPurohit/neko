package dev.neko.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.neko.core.*
import java.time.Instant
import java.time.LocalDate

@Composable fun CategoryPicker(category:Category,onChange:(Category)->Unit){
    var open by remember{mutableStateOf(false)}
    Box{OutlinedButton({open=true},Modifier.fillMaxWidth()){Text(category.label)};DropdownMenu(open,{open=false}){Category.entries.forEach{item->DropdownMenuItem(text={Text(item.label)},onClick={onChange(item);open=false})}}}
}
@Composable fun TransactionDialog(tx:Transaction?,state:NekoState,onDismiss:()->Unit,onSave:(Transaction,Category,String,String,Long,Long,PaymentStatus,SpendingTreatment,String?,Long?)->Unit,onMatch:((Transaction)->Unit)?=null,onUnmatch:(()->Unit)?=null,onOwnTransfer:(()->Unit)?=null){
    var amount by rememberSaveable(tx?.id){mutableStateOf(tx?.let{Money.decimal(it.amountPaise)}?:"")}
    var merchant by rememberSaveable(tx?.id){mutableStateOf(tx?.merchant?:"")};var notes by rememberSaveable(tx?.id){mutableStateOf(tx?.notes?:"")}
    var category by rememberSaveable(tx?.id){mutableStateOf(tx?.category?:Category.OTHER)};var account by rememberSaveable(tx?.id){mutableStateOf(tx?.account?:"Cash")}
    var direction by rememberSaveable(tx?.id){mutableStateOf(tx?.direction?:Direction.DEBIT)};var date by rememberSaveable(tx?.id){mutableLongStateOf(tx?.occurredAt?:System.currentTimeMillis())}
    var status by rememberSaveable(tx?.id){mutableStateOf(tx?.status?:PaymentStatus.POSTED)};var error by remember{mutableStateOf<String?>(null)};var matching by remember{mutableStateOf(false)}
    var treatment by rememberSaveable(tx?.id){mutableStateOf(tx?.spendingTreatment?:SpendingTreatment.AUTO)}
    var relatedId by rememberSaveable(tx?.id){mutableStateOf(tx?.relatedTransactionId)}
    var principal by rememberSaveable(tx?.id){mutableStateOf(tx?.principalPaise?.let(Money::decimal).orEmpty())}
    var treatmentMenu by remember{mutableStateOf(false)};var relatedMenu by remember{mutableStateOf(false)}
    val context=LocalContext.current
    // Any matching entry in another account is offered; accounts do not have to be marked as the user's own first.
    val candidate=tx?.takeIf{it.transferId==null&&it.relatedTransactionId==null}?.let{Transfers.counterpart(it,state.transactions)}
    val draft=tx?:Transaction(occurredAt=date,amountPaise=1,direction=direction,account=account,merchant=merchant,category=category)
    val effectiveTreatment=SpendingPolicy.treatment(draft.copy(direction=direction,merchant=merchant,category=category,spendingTreatment=treatment))
    val treatmentOptions=if(direction==Direction.DEBIT) listOf(SpendingTreatment.AUTO,SpendingTreatment.PERSONAL_SPENDING,SpendingTreatment.FD_PRINCIPAL,SpendingTreatment.INVESTMENT_PRINCIPAL,SpendingTreatment.TEMPORARY_MOVEMENT)
        else listOf(SpendingTreatment.AUTO,SpendingTreatment.REVIEW_REQUIRED,SpendingTreatment.FRIEND_REIMBURSEMENT,SpendingTreatment.FUNDING,SpendingTreatment.INCOME,SpendingTreatment.INVESTMENT_RETURN,SpendingTreatment.REFUND,SpendingTreatment.TEMPORARY_MOVEMENT)
    val linkedOptions=state.transactions.filter { other ->
        other.id!=tx?.id && other.status==PaymentStatus.POSTED && other.review==ReviewStatus.CONFIRMED && other.transferId==null && (other.relatedTransactionId==null || other.relatedTransactionId==tx?.id) &&
            if(effectiveTreatment==SpendingTreatment.FRIEND_REIMBURSEMENT||effectiveTreatment==SpendingTreatment.REFUND)
                other.direction==Direction.DEBIT&&SpendingPolicy.treatment(other)==SpendingTreatment.PERSONAL_SPENDING
            else effectiveTreatment==SpendingTreatment.TEMPORARY_MOVEMENT&&other.direction!=direction&&other.amountPaise==(runCatching{Money.parse(amount)}.getOrNull()?:-1L)
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(tx==null)"Add a transaction"else"Review transaction")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(tx!=null){StatusPill("${tx.source.name.lowercase()} · ${tx.status.name.lowercase()}",tx.status==PaymentStatus.POSTED);Text("Capture confidence ${(tx.confidence*100).toInt()}%",style=MaterialTheme.typography.bodySmall)}
        OutlinedTextField(amount,{amount=it},label={Text("Amount in INR")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true)
        OutlinedTextField(merchant,{merchant=it},label={Text("Merchant / counterparty")},singleLine=true)
        if(tx==null){OutlinedTextField(account,{account=it},label={Text("Account or Cash")},singleLine=true);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Direction.entries.forEach{d->FilterChip(direction==d,{direction=d},label={Text(d.name.lowercase())})}}}
        CategoryPicker(category){category=it}
        Text("Spending treatment",style=MaterialTheme.typography.labelLarge)
        Box { OutlinedButton({treatmentMenu=true},Modifier.fillMaxWidth()){Text(if(treatment==SpendingTreatment.AUTO) "Default · ${treatmentLabel(effectiveTreatment)}" else treatmentLabel(treatment))};DropdownMenu(treatmentMenu,{treatmentMenu=false}){treatmentOptions.forEach { option->DropdownMenuItem(text={Text(if(option==SpendingTreatment.AUTO)"Default (apply my rules)"else treatmentLabel(option))},onClick={treatment=option;relatedId=null;treatmentMenu=false})} } }
        Text(when(effectiveTreatment){
            SpendingTreatment.PERSONAL_SPENDING->"Counts toward your personal spending, including payments to friends and cash withdrawals."
            SpendingTreatment.FRIEND_REIMBURSEMENT->"Reduces the linked expense, even when the friend pays you in a later month."
            SpendingTreatment.REFUND->"Reverses part or all of a linked expense."
            SpendingTreatment.FUNDING->"Tracked as money received; it does not reduce spending or count as earnings."
            SpendingTreatment.FD_PRINCIPAL,SpendingTreatment.INVESTMENT_PRINCIPAL->"Money moved into savings or investments; excluded from spending."
            SpendingTreatment.INVESTMENT_RETURN->"Only the gain is counted as income; principal is excluded."
            SpendingTreatment.TEMPORARY_MOVEMENT->"Excluded only when linked to an equal, opposite transaction."
            SpendingTreatment.REVIEW_REQUIRED->"Kept out of income and spending until you identify what this incoming payment is."
            else->"Tracked as income separately from spending."
        },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(effectiveTreatment==SpendingTreatment.FRIEND_REIMBURSEMENT||effectiveTreatment==SpendingTreatment.REFUND||effectiveTreatment==SpendingTreatment.TEMPORARY_MOVEMENT){
            if(linkedOptions.isEmpty())Text(if(onOwnTransfer!=null)"There is nothing to link yet. If you moved money between your own accounts, use \"This is a transfer between my own accounts\" further down; it needs no link." else "There is nothing to link yet.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            val selectedRelated=state.transactions.firstOrNull{it.id==relatedId}
            Box { OutlinedButton({relatedMenu=true},Modifier.fillMaxWidth()){Text(selectedRelated?.let{"Linked to ${it.merchant} · ${rupees(it.amountPaise)}"}?:"Choose linked transaction")};DropdownMenu(relatedMenu,{relatedMenu=false}){linkedOptions.forEach{item->DropdownMenuItem(text={Text("${item.merchant} · ${rupees(item.amountPaise)}")},onClick={relatedId=item.id;relatedMenu=false})}} }
        }
        if(effectiveTreatment==SpendingTreatment.INVESTMENT_RETURN){OutlinedTextField(principal,{principal=it},label={Text("Returned principal in INR (enter 0 if none)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true)}
        Text("Payment status",style=MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){PaymentStatus.entries.take(2).forEach{s->FilterChip(status==s,{status=s},label={Text(s.name.lowercase())})}}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){PaymentStatus.entries.drop(2).forEach{s->FilterChip(status==s,{status=s},label={Text(s.name.lowercase())})}}
        OutlinedButton({val local=Instant.ofEpochMilli(date).atZone(Ledger.india);DatePickerDialog(context,{_,y,m,d->date=LocalDate.of(y,m+1,d).atTime(local.hour,local.minute).atZone(Ledger.india).toInstant().toEpochMilli()},local.year,local.monthValue-1,local.dayOfMonth).show()}){Text(dateText(date).substringBefore(" ·"))}
        TextButton({val local=Instant.ofEpochMilli(date).atZone(Ledger.india);TimePickerDialog(context,{_,h,m->date=local.toLocalDate().atTime(h,m).atZone(Ledger.india).toInstant().toEpochMilli()},local.hour,local.minute,false).show()}){Text("Time: ${dateText(date).substringAfter(" · ")}")}
        OutlinedTextField(notes,{notes=it},label={Text("Notes (stay on this device)")},minLines=2)
        // Moving money between your own accounts is not spending or income and needs no link to another transaction.
        if(tx!=null&&tx.transferId==null&&onOwnTransfer!=null)OutlinedButton(onOwnTransfer,Modifier.fillMaxWidth()){Text(if(candidate!=null)"This is my own transfer (pairs with ${candidate.account})" else "This is a transfer between my own accounts")}
        if(tx?.transferId!=null&&onUnmatch!=null)TextButton(onUnmatch){Text("Unlink own-account transfer")}
        else if(candidate!=null&&onMatch!=null)TextButton({matching=true}){Text("Match transfer with ${candidate.account}")}
        if(category==Category.TRANSFER&&tx?.transferId==null)Text("This still counts under the default rule until you match it to the other account entry.",style=MaterialTheme.typography.bodySmall)
        if(error!=null)Text(error!!,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton({try {
        require(merchant.isNotBlank()&&account.isNotBlank()){ "Enter a counterparty and account" }
        val paise=Money.parse(amount);require(date<=System.currentTimeMillis()+86_400_000){"Check the transaction date"}
        val base=tx?:Transaction(occurredAt=date,amountPaise=paise,direction=direction,account=account,merchant=merchant,review=ReviewStatus.CONFIRMED,paymentMethod=if(account.equals("Cash",true))"Cash"else"Manual")
        val principalValue=if(effectiveTreatment==SpendingTreatment.INVESTMENT_RETURN){require(principal.isNotBlank()){"Enter the returned principal, including 0 if there is none"};if(principal.trim()=="0")0L else Money.parse(principal)}else null
        require(principalValue==null||principalValue<=paise){"Principal cannot exceed the amount received"}
        require(effectiveTreatment !in setOf(SpendingTreatment.FRIEND_REIMBURSEMENT,SpendingTreatment.REFUND,SpendingTreatment.TEMPORARY_MOVEMENT)||relatedId!=null){if(onOwnTransfer!=null&&tx!=null&&tx.transferId==null)"Choose the transaction this money is linked to. If it was a transfer between your own accounts, cancel and tap \"This is a transfer between my own accounts\" instead." else "Choose the transaction this money is linked to"}
        onSave(base,category,merchant,notes,paise,date,status,treatment,relatedId,principalValue)
    }catch(e:Exception){error=e.message?:"Check the entered values"}}){Text(if(effectiveTreatment==SpendingTreatment.REVIEW_REQUIRED)"Save review draft"else if(tx==null)"Save entry"else"Confirm & save")}},dismissButton={TextButton(onDismiss){Text("Cancel")}})
    if(matching&&candidate!=null)AlertDialog(onDismissRequest={matching=false},title={Text("Confirm own transfer")},text={Text("Link these two entries for ${rupees(candidate.amountPaise)}? Check that the accounts and dates match. Both will be excluded from income and spending. You can unlink them later.")},confirmButton={TextButton({onMatch?.invoke(candidate);matching=false}){Text("Confirm transfer")}},dismissButton={TextButton({matching=false}){Text("Cancel")}})
}

private fun treatmentLabel(treatment:SpendingTreatment):String=when(treatment){
    SpendingTreatment.AUTO->"Default rules";SpendingTreatment.REVIEW_REQUIRED->"Needs review";SpendingTreatment.PERSONAL_SPENDING->"Personal spending";SpendingTreatment.FRIEND_REIMBURSEMENT->"Friend reimbursement"
    SpendingTreatment.FUNDING->"Pocket money / funding";SpendingTreatment.INCOME->"Income";SpendingTreatment.FD_PRINCIPAL->"FD principal"
    SpendingTreatment.INVESTMENT_PRINCIPAL->"Investment principal";SpendingTreatment.INVESTMENT_RETURN->"Investment return"
    SpendingTreatment.REFUND->"Refund / reversal";SpendingTreatment.TEMPORARY_MOVEMENT->"Temporary movement"
}
@Composable fun BudgetDialog(onDismiss:()->Unit,onSave:(Category,Long)->Unit){
    var category by remember{mutableStateOf(Category.FOOD)};var amount by remember{mutableStateOf("")};var error by remember{mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Set a monthly budget")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){CategoryPicker(category){category=it};OutlinedTextField(amount,{amount=it},label={Text("Monthly limit in INR")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal));if(error!=null)Text(error!!,color=MaterialTheme.colorScheme.error)}},confirmButton={TextButton({try{onSave(category,Money.parse(amount))}catch(e:Exception){error="Enter a positive amount with up to two decimals"}}){Text("Save budget")}},dismissButton={TextButton(onDismiss){Text("Cancel")}})
}
