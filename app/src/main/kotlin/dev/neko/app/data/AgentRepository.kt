package dev.neko.app.data

import dev.neko.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AgentRepository(private val ledger: LedgerRepository, private val network: Network) {
    private val settings get()=ledger.settings
    suspend fun api(path: String,method: String="GET",body: JSONObject?=null):JSONObject {
        require(settings.deviceToken.isNotBlank()){ "Connect your backend in Settings" }
        return network.request(settings.backendUrl+"/v1/"+path,settings.deviceToken,method,body)
    }
    suspend fun pair(url:String,secret:String,name:String) {
        require(url.startsWith("https://"))
        val result=network.request(url.trimEnd('/')+"/v1/pair",method="POST",json=JSONObject().put("secret",secret).put("name",name))
        settings.backendUrl=url;settings.deviceToken=result.getString("device_token");settings.put("user_id",result.getString("user_id"))
        val config=api("settings");settings.put("has_model_key",config.optBoolean("has_model_key").toString())
    }
    suspend fun login(url:String,email:String,password:String,name:String,registrationCode:String,register:Boolean) {
        val result=network.request(url.trimEnd('/')+"/v1/auth/"+(if(register)"register"else"login"),method="POST",json=JSONObject().put("email",email).put("password",password).put("name",name).put("registration_code",registrationCode))
        settings.backendUrl=url;settings.deviceToken=result.getString("device_token");settings.put("user_id",result.getString("user_id"));settings.put("user_name",result.getString("name"));settings.put("has_model_key",result.getBoolean("has_model_key").toString());settings.aiEnabled=result.getBoolean("ai_enabled")
    }
    suspend fun byok(key:String) { api("byok","POST",JSONObject().put("key",key));settings.put("has_model_key","true") }
    suspend fun removeByok(){api("byok","DELETE");settings.put("has_model_key","false");settings.aiEnabled=false}
    suspend fun logout(){try{api("auth/logout","POST")}finally{settings.deviceToken="";settings.aiEnabled=false;settings.put("has_model_key","false")}}
    suspend fun setAi(enabled:Boolean) {
        if(!enabled)settings.aiEnabled=false
        api("settings","PATCH",JSONObject().put("ai_enabled",enabled));settings.aiEnabled=enabled
        ledger.changed()
    }
    suspend fun sync():JSONObject? = withContext(Dispatchers.IO) {
        if(!settings.aiEnabled||settings.paused||settings.deviceToken.isBlank())return@withContext null
        val rows=ledger.db.transactions().take(150)
        // Whitelist fields; raw SMS, references, notes, account suffixes and keys never cross this boundary.
        fun safeMerchant(s:String)=s.replace(Regex("[\\w.+-]+@[\\w.-]+"),"Private counterparty").replace(Regex("\\b\\d{4,}\\b"),"Private number").take(100)
        val txs=JSONArray(rows.map { t -> JSONObject().put("id",t.id).put("occurred_at",t.occurredAt).put("amount_paise",t.amountPaise)
            .put("direction",t.direction.name).put("category",t.category.name).put("merchant",safeMerchant(t.merchant))
            .put("status",t.status.name).put("review",t.review.name).put("account_alias",t.account.substringBefore(" ·"))
            .put("transfer_id",t.transferId).put("updated_at",t.updatedAt)
            .put("spending_treatment",t.spendingTreatment.name).put("related_transaction_id",t.relatedTransactionId).put("principal_paise",t.principalPaise) })
        val budgets=JSONArray(ledger.db.budgets().map { JSONObject().put("category",it.category.name).put("amount_paise",it.amountPaise) })
        val result=api("sync","POST",JSONObject().put("transactions",txs).put("budgets",budgets))
        settings.put("last_sync",result.getLong("last_sync").toString())
        val activity=api("activity")
        settings.put("agent_state",activity.toString())
        val list=activity.getJSONArray("activity")
        for(i in 0 until list.length()) {
            val item=list.getJSONObject(i);ledger.db.saveActivity(item)
            if(item.getString("kind")=="chat")ledger.db.addChat("assistant",item.getString("body"),item.getString("id"))
        }
        ledger.changed();activity
    }
    suspend fun chat(message:String) {
        require(settings.aiEnabled){ "Enable cloud AI in Settings to talk with Neko" }
        sync()
        api("chat","POST",JSONObject().put("message",message))
        withContext(Dispatchers.IO){ledger.db.addChat("user",message);ledger.changed()}
    }
    suspend fun addGoal(title:String,hour:Int) { api("goals","POST",JSONObject().put("title",title).put("hour",hour));sync() }
    suspend fun pauseGoal(id:String,enabled:Boolean){api("goals/$id","PATCH",JSONObject().put("enabled",enabled));sync()}
    suspend fun eraseCloudContext(){api("cloud-context","DELETE");settings.aiEnabled=false;settings.put("last_sync","0");settings.put("agent_state","{}");ledger.changed()}
}
