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
    put("personalSharePaise", personalSharePaise)
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
    personalSharePaise=if(!j.has("personalSharePaise")||j.isNull("personalSharePaise"))null else j.optLong("personalSharePaise"),
)

class NekoDatabase(context: Context): SQLiteOpenHelper(context,"neko.db",null,2) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE ledger(id TEXT PRIMARY KEY, fingerprint TEXT UNIQUE NOT NULL, occurred_at INTEGER NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE raw_sms(fingerprint TEXT PRIMARY KEY, ciphertext TEXT NOT NULL)")
        db.execSQL("CREATE TABLE budgets(category TEXT PRIMARY KEY, amount_paise INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE activity(id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE chat(id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, role TEXT NOT NULL, message TEXT NOT NULL)")
        db.execSQL("CREATE TABLE own_accounts(account TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE split_posts(id TEXT PRIMARY KEY, remote_id TEXT, state TEXT NOT NULL)")
        createLocalSplits(db)
    }
    /** Version 2: the local Splitwise (people, splits and settle-ups). */
    private fun createLocalSplits(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS people(name TEXT PRIMARY KEY COLLATE NOCASE, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS local_splits(id TEXT PRIMARY KEY, kind TEXT NOT NULL, created_at INTEGER NOT NULL, data TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<2)createLocalSplits(db)
        if(newVersion>2)error("A tested migration is required")
    }
    fun people(): List<String> = readableDatabase.rawQuery("SELECT name FROM people ORDER BY name COLLATE NOCASE",null).use { c -> buildList { while(c.moveToNext())add(c.getString(0)) } }
    fun addPerson(name: String) { writableDatabase.insertWithOnConflict("people",null,ContentValues().apply { put("name",name.trim());put("created_at",System.currentTimeMillis()) },SQLiteDatabase.CONFLICT_IGNORE) }
    private fun entries(kind: String): List<JSONObject> = readableDatabase.rawQuery("SELECT data FROM local_splits WHERE kind=? ORDER BY created_at DESC",arrayOf(kind)).use { c -> buildList { while(c.moveToNext())add(JSONObject(c.getString(0))) } }
    private fun saveEntry(id: String, kind: String, data: JSONObject) {
        val created=readableDatabase.rawQuery("SELECT created_at FROM local_splits WHERE id=?",arrayOf(id)).use { if(it.moveToFirst())it.getLong(0) else System.currentTimeMillis() }
        writableDatabase.insertWithOnConflict("local_splits",null,ContentValues().apply { put("id",id);put("kind",kind);put("created_at",created);put("data",data.toString()) },SQLiteDatabase.CONFLICT_REPLACE)
    }
    private fun JSONObject.strings(name: String): Map<String,String> = optJSONObject(name)?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } }.orEmpty()
    fun expenses(): List<SplitExpense> = entries("split").mapNotNull { j -> runCatching {
        val total=j.optLong("total_paise")
        // Splits saved before uneven splits held only what friends owed; the rest was the user's part.
        val shares=j.optJSONObject("shares")?.let { o -> o.keys().asSequence().associateWith { o.getLong(it) } }
            ?: j.getJSONArray("shares").let { a -> val friends=(0 until a.length()).associate { i -> a.getJSONObject(i).let { it.getString("person") to it.getLong("paise") } }; mapOf(ME to total-friends.values.sum())+friends }
        SplitExpense(j.getString("id"),j.getString("title"),total,j.getLong("at"),j.optString("paid_by",ME),shares,
            runCatching { SplitMethod.valueOf(j.optString("method")) }.getOrDefault(SplitMethod.EQUAL),j.strings("inputs"),
            j.optString("transaction_id").takeIf { it.isNotBlank()&&it!="null" },j.optString("group_id").takeIf { it.isNotBlank()&&it!="null" },
            runCatching { Category.valueOf(j.optString("category")) }.getOrDefault(Category.OTHER),j.optString("notes"),
            runCatching { Repeat.valueOf(j.optString("repeat")) }.getOrDefault(Repeat.NONE),j.optString("series_id").takeIf { it.isNotBlank()&&it!="null" })
    }.getOrNull() }
    fun saveExpense(e: SplitExpense) = saveEntry(e.id,"split",JSONObject().put("id",e.id).put("title",e.title).put("total_paise",e.totalPaise).put("at",e.at).put("paid_by",e.paidBy)
        .put("shares",JSONObject(e.shares as Map<*,*>)).put("method",e.method.name).put("inputs",JSONObject(e.inputs as Map<*,*>)).put("transaction_id",e.transactionId).put("group_id",e.groupId)
        .put("category",e.category.name).put("notes",e.notes).put("repeat",e.repeat.name).put("series_id",e.seriesId))
    fun settlements(): List<Settlement> = entries("settle").mapNotNull { j -> runCatching {
        Settlement(j.getString("id"),j.optString("from").ifBlank { j.getString("person") },j.optString("to").ifBlank { ME },j.getLong("paise"),j.getLong("at"),j.optString("group_id").takeIf { it.isNotBlank()&&it!="null" })
    }.getOrNull() }
    fun saveSettlement(s: Settlement) = saveEntry(s.id,"settle",JSONObject().put("id",s.id).put("from",s.from).put("to",s.to).put("paise",s.paise).put("at",s.at).put("group_id",s.groupId))
    fun groups(): List<SplitGroup> = entries("group").mapNotNull { j -> runCatching { SplitGroup(j.getString("id"),j.getString("name"),j.getJSONArray("members").let { a -> (0 until a.length()).map(a::getString) }) }.getOrNull() }
    fun saveGroup(g: SplitGroup) = saveEntry(g.id,"group",JSONObject().put("id",g.id).put("name",g.name).put("members",org.json.JSONArray(g.members)))
    fun deleteSplitEntry(id: String) { writableDatabase.delete("local_splits","id=?",arrayOf(id)) }
    fun transactions(): List<Transaction> = readableDatabase.rawQuery("SELECT data FROM ledger ORDER BY occurred_at DESC",null).use { c -> buildList { while(c.moveToNext()) add(transactionFromJson(JSONObject(c.getString(0)))) } }
    fun get(id: String): Transaction? = readableDatabase.rawQuery("SELECT data FROM ledger WHERE id=?", arrayOf(id)).use { c -> if(c.moveToFirst())transactionFromJson(JSONObject(c.getString(0)))else null }
    fun save(tx: Transaction) { writableDatabase.insertWithOnConflict("ledger",null,ContentValues().apply { put("id",tx.id);put("fingerprint",tx.fingerprint);put("occurred_at",tx.occurredAt);put("data",tx.toJson().toString()) },SQLiteDatabase.CONFLICT_REPLACE) }
    fun delete(id: String) { writableDatabase.delete("ledger","id=?",arrayOf(id)) }
    fun getByFingerprint(fingerprint: String): Transaction? = readableDatabase.rawQuery("SELECT data FROM ledger WHERE fingerprint=?", arrayOf(fingerprint)).use { c -> if(c.moveToFirst())transactionFromJson(JSONObject(c.getString(0)))else null }
    /** Sets the limit for each of [limits] and removes the limit of every other category in [asked], all or nothing. */
    fun replaceBudgets(asked: Collection<Category>, limits: Map<Category, Long>) {
        val database=writableDatabase;database.beginTransaction()
        try {
            asked.filter { it !in limits }.forEach { database.delete("budgets","category=?",arrayOf(it.name)) }
            limits.forEach { (category,amount) -> saveBudget(Budget(category,amount)) }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }
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
