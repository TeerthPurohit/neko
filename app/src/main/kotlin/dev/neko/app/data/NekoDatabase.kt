package dev.neko.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.neko.core.*
import org.json.JSONObject

fun Transaction.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("occurredAt", occurredAt); put("amountPaise", amountPaise); put("direction", direction.name)
    put("account", account); put("merchant", merchant); put("category", category.name); put("paymentMethod", paymentMethod)
    put("notes", notes); put("confidence", confidence); put("source", source.name); put("review", review.name)
    put("status", status.name); put("reference", reference); put("fingerprint", fingerprint); put("transferId", transferId)
    put("updatedAt", updatedAt); put("aiSuggestedCategory", aiSuggestedCategory?.name); put("aiConfidence", aiConfidence); put("revision", revision)
    put("spendingTreatment", spendingTreatment.name); put("relatedTransactionId", relatedTransactionId); put("principalPaise", principalPaise)
}
fun transactionFromJson(j: JSONObject): Transaction = Transaction(
    id=j.getString("id"),occurredAt=j.getLong("occurredAt"),amountPaise=j.getLong("amountPaise"),direction=Direction.valueOf(j.getString("direction")),
    account=j.getString("account"),merchant=j.getString("merchant"),category=Category.valueOf(j.getString("category")),paymentMethod=j.getString("paymentMethod"),
    notes=j.optString("notes"),confidence=j.getDouble("confidence"),source=Source.valueOf(j.getString("source")),review=ReviewStatus.valueOf(j.getString("review")),
    status=PaymentStatus.valueOf(j.getString("status")),reference=if(j.isNull("reference"))null else j.getString("reference"),fingerprint=j.getString("fingerprint"),
    transferId=if(j.isNull("transferId"))null else j.getString("transferId"),updatedAt=j.getLong("updatedAt"),
    aiSuggestedCategory=if(j.isNull("aiSuggestedCategory"))null else Category.valueOf(j.getString("aiSuggestedCategory")),
    aiConfidence=if(j.isNull("aiConfidence"))null else j.getDouble("aiConfidence"),revision=j.optInt("revision",1),
    spendingTreatment=runCatching { SpendingTreatment.valueOf(j.optString("spendingTreatment","AUTO")) }.getOrDefault(SpendingTreatment.AUTO),
    relatedTransactionId=j.optString("relatedTransactionId").takeIf { it.isNotBlank() },
    principalPaise=if(j.isNull("principalPaise"))null else j.optLong("principalPaise"),
)

class NekoDatabase(context: Context): SQLiteOpenHelper(context,"neko.db",null,1) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE ledger(id TEXT PRIMARY KEY, fingerprint TEXT UNIQUE NOT NULL, occurred_at INTEGER NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE raw_sms(fingerprint TEXT PRIMARY KEY, ciphertext TEXT NOT NULL)")
        db.execSQL("CREATE TABLE budgets(category TEXT PRIMARY KEY, amount_paise INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE activity(id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE chat(id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, role TEXT NOT NULL, message TEXT NOT NULL)")
        db.execSQL("CREATE TABLE own_accounts(account TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE split_posts(id TEXT PRIMARY KEY, remote_id TEXT, state TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("A tested migration is required") }
    fun transactions(): List<Transaction> = readableDatabase.rawQuery("SELECT data FROM ledger ORDER BY occurred_at DESC",null).use { c -> buildList { while(c.moveToNext()) add(transactionFromJson(JSONObject(c.getString(0)))) } }
    fun get(id: String): Transaction? = readableDatabase.rawQuery("SELECT data FROM ledger WHERE id=?", arrayOf(id)).use { c -> if(c.moveToFirst())transactionFromJson(JSONObject(c.getString(0)))else null }
    fun save(tx: Transaction) { writableDatabase.insertWithOnConflict("ledger",null,ContentValues().apply { put("id",tx.id);put("fingerprint",tx.fingerprint);put("occurred_at",tx.occurredAt);put("data",tx.toJson().toString()) },SQLiteDatabase.CONFLICT_REPLACE) }
    fun budgets(): List<Budget> = readableDatabase.rawQuery("SELECT category,amount_paise FROM budgets",null).use { c -> buildList { while(c.moveToNext())add(Budget(Category.valueOf(c.getString(0)),c.getLong(1))) } }
    fun saveBudget(budget: Budget) { require(budget.amountPaise>0);writableDatabase.insertWithOnConflict("budgets",null,ContentValues().apply { put("category",budget.category.name);put("amount_paise",budget.amountPaise) },SQLiteDatabase.CONFLICT_REPLACE) }
    fun ownAccounts(): Set<String> = readableDatabase.rawQuery("SELECT account FROM own_accounts",null).use { c -> buildSet { while(c.moveToNext())add(c.getString(0)) } }
    fun setOwned(account: String, owned: Boolean) { if(owned)writableDatabase.insertWithOnConflict("own_accounts",null,ContentValues().apply { put("account",account) },SQLiteDatabase.CONFLICT_IGNORE) else writableDatabase.delete("own_accounts","account=?",arrayOf(account)) }
    fun saveActivity(j: JSONObject) { writableDatabase.insertWithOnConflict("activity",null,ContentValues().apply { put("id",j.getString("id"));put("created_at",j.getLong("created_at"));put("data",j.toString()) },SQLiteDatabase.CONFLICT_REPLACE) }
    fun activity(): List<JSONObject> = readableDatabase.rawQuery("SELECT data FROM activity ORDER BY created_at DESC LIMIT 100",null).use { c -> buildList { while(c.moveToNext())add(JSONObject(c.getString(0))) } }
    fun addChat(role: String, message: String, id: String = java.util.UUID.randomUUID().toString()) { writableDatabase.insertWithOnConflict("chat",null,ContentValues().apply { put("id",id);put("created_at",System.currentTimeMillis());put("role",role);put("message",message) },SQLiteDatabase.CONFLICT_IGNORE) }
    fun hasChat(id: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM chat WHERE id=? LIMIT 1",arrayOf(id)).use { it.moveToFirst() }
    fun chat(): List<Pair<String,String>> = readableDatabase.rawQuery("SELECT role,message FROM chat ORDER BY created_at LIMIT 200",null).use { c -> buildList { while(c.moveToNext())add(c.getString(0) to c.getString(1)) } }
}
