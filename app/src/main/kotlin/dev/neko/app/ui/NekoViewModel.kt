package dev.neko.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.neko.app.NekoApplication
import dev.neko.app.agent.AgentWork
import dev.neko.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

data class ActivityItem(val id:String,val title:String,val body:String,val kind:String,val createdAt:Long,val proposals:List<Proposal>)
data class Proposal(val transactionId:String,val category:Category,val expectedUpdatedAt:Long,val reason:String)
data class GoalItem(val id:String,val title:String,val hour:Int,val enabled:Boolean)
data class GroupItem(val id:String,val name:String,val source:String,val members:Int=0)
data class ExpenseItem(val title:String,val cost:String,val currency:String,val groupId:String)
data class NekoState(
    val loading:Boolean=true,val busy:Boolean=false,val error:String?=null,val message:String?=null,
    val transactions:List<Transaction> = emptyList(),val budgets:List<Budget> = emptyList(),val ownAccounts:Set<String> = emptySet(),
    val activity:List<ActivityItem> = emptyList(),val chat:List<Pair<String,String>> = emptyList(),val goals:List<GoalItem> = emptyList(),
    val groups:List<GroupItem> = emptyList(),val expenses:List<ExpenseItem> = emptyList(),
    val theme:String="system",val reducedMotion:Boolean=false,val aiEnabled:Boolean=false,val paired:Boolean=false,val paused:Boolean=false,
    val backendUrl:String="",val splitwiseConnected:Boolean=false,val model:String="xiaomi/mimo-v2.6-pro",val models:List<String> = listOf("xiaomi/mimo-v2.6-pro"),
    val lastSync:Long=0,val usageRequests:Int=0,val usageCost:Double=0.0,val userName:String="You",val pendingTasks:Int=0,
    val hasModelKey:Boolean=false,val offlineProfile:Boolean=false,val byokDeferred:Boolean=false,val firebaseConfigured:Boolean=false,
)

class NekoViewModel(private val app:NekoApplication):ViewModel() {
    private val mutable=MutableStateFlow(NekoState())
    val state:StateFlow<NekoState> = mutable.asStateFlow()
    private var observer:Job?=null
    fun start() {
        if(observer?.isActive==true)return
        observer=viewModelScope.launch { app.ledger.changes.collect { reload() } }
    }
    private suspend fun reload() {
        val result=withContext(Dispatchers.IO) {
            val s=app.settings;val agent=JSONObject(s.get("agent_state","{}"));val goals=agent.optJSONArray("goals")?:JSONArray();val usage=agent.optJSONArray("usage")?:JSONArray()
            val splitGroups=JSONArray(s.get("splitwise_groups","[]"));val splitExpenses=JSONArray(s.get("splitwise_expenses","[]"));val cloudGroups=JSONArray(s.get("neko_groups","[]"))
            mutable.value.copy(loading=false,transactions=app.ledger.db.transactions(),budgets=app.ledger.db.budgets(),ownAccounts=app.ledger.db.ownAccounts(),
                activity=app.ledger.db.activity().map { j ->
                    val raw=j.optString("proposal","null");val list=if(raw.startsWith("["))JSONArray(raw) else if(raw.startsWith("{"))JSONArray().put(JSONObject(raw))else JSONArray()
                    ActivityItem(j.getString("id"),j.getString("title"),j.getString("body"),j.getString("kind"),j.getLong("created_at"),(0 until list.length()).mapNotNull { i -> try {val p=list.getJSONObject(i);Proposal(p.getString("transaction_id"),Category.valueOf(p.getString("category")),p.getLong("expected_updated_at"),p.getString("reason"))}catch(_:Exception){null} })
                },chat=app.ledger.db.chat(),goals=(0 until goals.length()).map {i->goals.getJSONObject(i).let{GoalItem(it.getString("id"),it.getString("title"),it.getInt("hour"),it.getInt("enabled")==1)}},
                groups=(0 until splitGroups.length()).mapNotNull { i->splitGroups.getJSONObject(i).let { if(it.getLong("id")==0L)null else GroupItem(it.getLong("id").toString(),it.getString("name"),"Splitwise",it.optJSONArray("members")?.length()?:0) } } + (0 until cloudGroups.length()).map {i->cloudGroups.getJSONObject(i).let{GroupItem(it.getString("id"),it.getString("name"),"Neko")}},
                expenses=(0 until splitExpenses.length()).mapNotNull { i->splitExpenses.getJSONObject(i).let { if(!it.isNull("deleted_at"))null else ExpenseItem(it.getString("description"),it.getString("cost"),it.getString("currency_code"),it.optLong("group_id").toString()) } },
                theme=s.theme,reducedMotion=s.reducedMotion,aiEnabled=s.aiEnabled,paired=s.deviceToken.isNotBlank(),paused=s.paused,backendUrl=s.backendUrl,splitwiseConnected=s.splitwiseKey.isNotBlank(),lastSync=s.get("last_sync","0").toLong(),model=s.get("model","xiaomi/mimo-v2.6-pro"),models=s.get("models","xiaomi/mimo-v2.6-pro").split(','),
                usageRequests=(0 until usage.length()).sumOf {usage.getJSONObject(it).getInt("requests")},usageCost=(0 until usage.length()).sumOf {usage.getJSONObject(it).getDouble("cost")},userName=s.get("user_name","You"),pendingTasks=agent.optJSONArray("tasks")?.let { tasks->(0 until tasks.length()).count {tasks.getJSONObject(it).optString("status") in listOf("queued","running")} }?:0,
                hasModelKey=s.get("has_model_key","false").toBoolean(),offlineProfile=s.get("offline_profile","false").toBoolean(),byokDeferred=s.get("byok_deferred","false").toBoolean(),
                firebaseConfigured=com.google.firebase.FirebaseApp.getApps(app).isNotEmpty(),
            )
        }
        // A concurrent data refresh must not restore an action's old busy/error state.
        mutable.update { current -> result.copy(busy=current.busy,error=current.error,message=current.message) }
    }
    fun action(block:suspend ()->Unit) {
        if(mutable.value.busy)return
        mutable.update{it.copy(busy=true,error=null,message=null)}
        viewModelScope.launch {
            try{block();reload()}catch(error:CancellationException){throw error}catch(error:Exception){mutable.update{it.copy(error=error.message?:"Could not complete this action")}}
            finally{mutable.update{it.copy(busy=false)}}
        }
    }
    fun dismiss(){mutable.update{it.copy(error=null,message=null)}}
    fun note(message:String){mutable.update{it.copy(message=message)}}
    fun refresh()=action {
        app.agent.sync()
        if(app.settings.splitwiseKey.isNotBlank())app.splitwise.refresh()
        if(app.settings.deviceToken.isNotBlank()) {
            val config=app.agent.api("settings");app.settings.put("model",config.getString("model"));app.settings.put("models",(0 until config.getJSONArray("models").length()).joinToString(","){config.getJSONArray("models").getString(it)})
            app.settings.put("neko_groups",app.agent.api("groups").getJSONArray("groups").toString())
        }
    }
    fun save(tx:Transaction)=action{app.ledger.saveManual(tx);AgentWork.syncNow(app)}
    fun correct(tx:Transaction,category:Category,merchant:String,notes:String,amount:Long,date:Long,status:PaymentStatus,treatment:SpendingTreatment=tx.spendingTreatment,relatedId:String?=tx.relatedTransactionId,principalPaise:Long?=tx.principalPaise)=action{app.ledger.correct(tx.id,tx.revision,category,merchant,notes,amount,date,status,treatment,relatedId,principalPaise);AgentWork.syncNow(app)}
    fun match(a:String,b:String)=action{app.ledger.matchTransfer(a,b);AgentWork.syncNow(app)}
    fun unmatch(id:String)=action{app.ledger.unmatchTransfer(id);AgentWork.syncNow(app)}
    fun budget(category:Category,amount:Long)=action{withContext(Dispatchers.IO){app.ledger.db.saveBudget(Budget(category,amount));app.ledger.changed()};AgentWork.syncNow(app)}
    fun setOwned(account:String,value:Boolean)=action{withContext(Dispatchers.IO){app.ledger.db.setOwned(account,value);app.ledger.changed()}}
    fun theme(value:String)=action{app.settings.theme=value;app.ledger.changed()}
    fun motion(value:Boolean)=action{app.settings.reducedMotion=value;app.ledger.changed()}
    fun pause(value:Boolean)=action{app.settings.paused=value;app.ledger.changed();for(goal in mutable.value.goals)app.agent.pauseGoal(goal.id,!value)}
    fun pair(url:String,key:String,name:String)=action{app.agent.pair(url,key,name);app.settings.put("user_name",name);app.ledger.changed();note("Backend connected. You can now enable cloud AI.")}
    fun login(url:String,email:String,password:String,name:String,registrationCode:String,register:Boolean)=action{app.agent.login(url,email,password,name,registrationCode,register);app.settings.put("offline_profile","false");app.settings.put("byok_deferred","false");app.ledger.changed()}
    fun byok(key:String)=action{app.agent.byok(key);app.agent.setAi(true);app.agent.sync();app.ledger.changed()}
    fun removeByok()=action{app.agent.removeByok();app.ledger.changed()}
    fun offline()=action{app.settings.put("offline_profile","true");app.ledger.changed()}
    fun deferByok()=action{app.settings.put("byok_deferred","true");app.ledger.changed()}
    fun logout()=action{app.agent.logout();app.settings.put("offline_profile","false");app.ledger.changed()}
    fun ai(value:Boolean)=action{app.agent.setAi(value);if(value){app.agent.sync();AgentWork.syncNow(app)}}
    fun model(value:String)=action{app.agent.api("settings","PATCH",JSONObject().put("model",value));app.settings.put("model",value);app.ledger.changed()}
    fun eraseCloud()=action{app.agent.eraseCloudContext()}
    fun splitwise(key:String)=action{app.splitwise.connect(key)}
    fun disconnectSplitwise()=action{app.settings.splitwiseKey="";app.settings.remove("splitwise_groups");app.settings.remove("splitwise_expenses");app.ledger.changed()}
    fun send(message:String)=action{app.agent.chat(message);AgentWork.syncNow(app)}
    fun goal(title:String,hour:Int)=action{app.agent.addGoal(title,hour)}
    fun goalEnabled(id:String,value:Boolean)=action{app.agent.pauseGoal(id,value)}
    fun applyProposal(proposal:Proposal)=action {
        val tx=withContext(Dispatchers.IO){app.ledger.db.get(proposal.transactionId)}?:error("This transaction is no longer available")
        require(tx.updatedAt==proposal.expectedUpdatedAt){"This suggestion is stale. Review the latest transaction instead."}
        app.ledger.correct(tx.id,tx.revision,proposal.category,tx.merchant,tx.notes,tx.amountPaise,tx.occurredAt,tx.status,tx.spendingTreatment,tx.relatedTransactionId,tx.principalPaise);AgentWork.syncNow(app)
    }
    fun createGroup(name:String)=action{
        val result=app.agent.api("groups","POST",JSONObject().put("name",name));app.settings.put("neko_groups",app.agent.api("groups").getJSONArray("groups").toString());app.ledger.changed();note("Group created. Invite code (valid for 7 days): ${result.getString("invite")}")
    }
    fun joinGroup(invite:String)=action{app.agent.api("groups/join","POST",JSONObject().put("invite",invite));app.settings.put("neko_groups",app.agent.api("groups").getJSONArray("groups").toString());app.ledger.changed()}
    suspend fun groupDetails(group:GroupItem):String {
        val result=app.agent.api("groups/${group.id}");val members=result.getJSONArray("members");val expenses=result.getJSONArray("expenses")
        val balances=mutableMapOf<String,Long>();val seen=mutableSetOf<String>()
        for(i in 0 until expenses.length()){val e=expenses.getJSONObject(i);val id=e.getString("id");if(seen.add(id)){val payer=e.getString("payer_id");balances[payer]=(balances[payer]?:0)+e.getLong("amount_paise")};val idUser=e.getString("user_id");balances[idUser]=(balances[idUser]?:0)-e.getLong("share_paise")}
        return group.name+"\n\n"+(0 until members.length()).joinToString("\n"){i->val m=members.getJSONObject(i);val balance=balances[m.getString("id")]?:0;"${m.getString("name")}: ${if(balance>=0)"gets back"else"owes"} ${rupees(kotlin.math.abs(balance))}"}+"\n\nBalances cover up to 500 returned share rows. Settlements are not yet recorded in this preview."
    }
    fun split(group:GroupItem,amount:Long,title:String,requestId:String)=action {
        if(group.source=="Splitwise")app.splitwise.createEqualExpense(group.id.toLong(),amount,title,requestId)
        else app.agent.api("groups/${group.id}/expenses","POST",JSONObject().put("amount_paise",amount).put("title",title).put("idempotency_key",requestId).put("confirmed",true))
        note("Expense posted with your confirmation.");app.ledger.changed()
    }
    fun firebase(config:String)=action{
        val j=JSONObject(config);val info=j.getJSONObject("project_info");val clients=j.getJSONArray("client")
        val client=(0 until clients.length()).map{clients.getJSONObject(it)}.firstOrNull{it.getJSONObject("client_info").getJSONObject("android_client_info").getString("package_name")=="dev.neko.app"}?:error("Firebase configuration must use package dev.neko.app")
        app.settings.put("firebase_app_id",client.getJSONObject("client_info").getString("mobilesdk_app_id"));app.settings.put("firebase_api_key",client.getJSONArray("api_key").getJSONObject(0).getString("current_key"));app.settings.put("firebase_sender_id",info.getString("project_number"));app.settings.put("firebase_project_id",info.getString("project_id"));app.configureFirebase();note("Push configuration saved.")
    }
}
