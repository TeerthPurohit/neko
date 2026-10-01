package dev.neko.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import dev.neko.core.Money

class SplitwiseRepository(private val ledger: LedgerRepository,private val network: Network) {
    private suspend fun api(path:String,method:String="GET",body:JSONObject?=null):JSONObject {
        require(ledger.settings.splitwiseKey.isNotBlank()){ "Connect Splitwise in Settings" }
        val result=network.request("https://secure.splitwise.com/api/v3.0/$path",ledger.settings.splitwiseKey,method,body)
        val errors=result.opt("errors")
        if(errors!=null&&errors!=JSONObject.NULL&&errors.toString() !in listOf("{}","[]",""))error("Splitwise rejected the request. Check your account and expense details.")
        return result
    }
    suspend fun connect(key:String) {
        val previous=ledger.settings.splitwiseKey;ledger.settings.splitwiseKey=key
        try { val user=api("get_current_user").getJSONObject("user");ledger.settings.put("splitwise_user",user.toString());refresh() }
        catch(error:Exception){ledger.settings.splitwiseKey=previous;throw error}
    }
    suspend fun refresh() {
        val groups=api("get_groups").getJSONArray("groups")
        val expenses=api("get_expenses?limit=100").getJSONArray("expenses")
        ledger.settings.put("splitwise_groups",groups.toString());ledger.settings.put("splitwise_expenses",expenses.toString());ledger.settings.put("splitwise_synced",System.currentTimeMillis().toString());ledger.changed()
    }
    suspend fun createEqualExpense(groupId:Long,amountPaise:Long,title:String,requestId:String) = withContext(Dispatchers.IO) {
        require(groupId>0&&amountPaise>0&&title.isNotBlank())
        val db=ledger.db.writableDatabase
        // Claim before the HTTP write. Ambiguous timeouts must never cause an automatic repost.
        val inserted=db.insertWithOnConflict("split_posts",null,ContentValues().apply { put("id",requestId);put("state","sending") },SQLiteDatabase.CONFLICT_IGNORE)
        require(inserted!=-1L){ "This request was already sent. Verify the expense in Splitwise before attempting another." }
        try {
            val result=api("create_expense","POST",JSONObject().put("cost",Money.decimal(amountPaise)).put("description",title.take(100)).put("currency_code","INR").put("group_id",groupId).put("split_equally",true).put("details","Created with your confirmation in Neko. Reference: $requestId"))
            val expense=result.getJSONArray("expenses").getJSONObject(0)
            db.update("split_posts",ContentValues().apply { put("state","posted");put("remote_id",expense.getLong("id").toString()) },"id=?",arrayOf(requestId))
            refresh()
        } catch(error:Exception) {
            db.update("split_posts",ContentValues().apply{put("state","unknown")},"id=?",arrayOf(requestId))
            throw java.io.IOException("Splitwise outcome needs checking. Neko will not automatically repost this request.",error)
        }
    }
}
