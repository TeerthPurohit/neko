package dev.neko.app.data

import dev.neko.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LedgerRepository(val db: NekoDatabase, val settings: SecureSettings) {
    val changes = MutableStateFlow(0L)
    fun changed() { changes.value = changes.value + 1 }
    suspend fun transactions(): List<Transaction> = withContext(Dispatchers.IO) { db.transactions() }
    suspend fun capture(sms: BankSms): Transaction? = withContext(Dispatchers.IO) {
        val incoming = SmsParser().parse(sms) ?: return@withContext null
        val database=db.writableDatabase;database.beginTransaction()
        try {
            val old=db.get(incoming.id)
            if(old!=null&&old.status==incoming.status&&old.amountPaise==incoming.amountPaise) { database.setTransactionSuccessful();return@withContext null }
            val conflict=old!=null&&(old.amountPaise!=incoming.amountPaise||old.direction!=incoming.direction)
            val tx=if(old==null)incoming else old.copy(status=incoming.status,review=if(conflict)ReviewStatus.DRAFT else old.review,notes=if(conflict)"Conflicting bank notice. Check encrypted original notices before confirming." else old.notes,updatedAt=System.currentTimeMillis(),revision=old.revision+1)
            db.save(tx)
            database.insertWithOnConflict("raw_sms",null,android.content.ContentValues().apply { put("fingerprint",tx.fingerprint);put("ciphertext",settings.encrypt(JSONObject().put("sender",sms.sender).put("body",sms.body).toString(),"sms:"+tx.fingerprint)) },android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            if(incoming.status==PaymentStatus.REVERSED&&incoming.reference!=null) {
                db.transactions().filter { it.id!=incoming.id&&it.account==incoming.account&&it.reference==incoming.reference&&it.amountPaise==incoming.amountPaise&&it.direction!=incoming.direction }.forEach { db.save(it.copy(status=PaymentStatus.REVERSED,updatedAt=System.currentTimeMillis(),revision=it.revision+1)) }
            }
            database.setTransactionSuccessful();changed();tx
        } finally { database.endTransaction() }
    }
    suspend fun saveManual(tx: Transaction, treatment: SpendingTreatment = tx.spendingTreatment, relatedId: String? = tx.relatedTransactionId, principalPaise: Long? = tx.principalPaise) = withContext(Dispatchers.IO) {
        persistPolicy(tx, null, treatment, relatedId, principalPaise, Source.MANUAL)
    }
    suspend fun correct(id: String, expectedRevision: Int, category: Category, merchant: String, notes: String, amount: Long, occurredAt: Long, status: PaymentStatus, treatment: SpendingTreatment, relatedId: String?, principalPaise: Long?) = withContext(Dispatchers.IO) {
        val database=db.writableDatabase;database.beginTransaction()
        try {
            val tx=db.get(id)?:error("Transaction no longer exists")
            require(tx.revision==expectedRevision){"This transaction changed. Open the latest version before saving."}
            require(tx.transferId==null||amount==tx.amountPaise){"Unlink the transfer before changing its amount."}
            val edited=tx.copy(category=category,merchant=merchant,notes=notes,amountPaise=amount,occurredAt=occurredAt,status=status)
            savePolicyRows(edited, treatment, relatedId, principalPaise, tx)
            database.setTransactionSuccessful();changed()
        } finally { database.endTransaction() }
    }
    private fun persistPolicy(tx: Transaction, expectedRevision: Int?, treatment: SpendingTreatment, relatedId: String?, principalPaise: Long?, source: Source) {
        val database=db.writableDatabase;database.beginTransaction()
        try {
            val old=db.get(tx.id)
            require(old == null && expectedRevision == null) { "This transaction already exists." }
            savePolicyRows(tx.copy(source=source), treatment, relatedId, principalPaise, old)
            database.setTransactionSuccessful();changed()
        } finally { database.endTransaction() }
    }
    private fun savePolicyRows(tx: Transaction, treatment: SpendingTreatment, relatedId: String?, principalPaise: Long?, old: Transaction?) {
        require(principalPaise == null || (treatment == SpendingTreatment.INVESTMENT_RETURN && principalPaise in 0..tx.amountPaise)) {
            "Investment principal must be between zero and the returned amount."
        }
        require(treatment != SpendingTreatment.INVESTMENT_RETURN || principalPaise != null) { "Investment return must include the returned principal." }
        if (tx.transferId != null) require(treatment == SpendingTreatment.AUTO && relatedId == null) { "Unlink the own-account transfer before changing its treatment." }
        val allowed = if(tx.direction == Direction.DEBIT) setOf(SpendingTreatment.AUTO,SpendingTreatment.PERSONAL_SPENDING,SpendingTreatment.FD_PRINCIPAL,SpendingTreatment.INVESTMENT_PRINCIPAL,SpendingTreatment.TEMPORARY_MOVEMENT)
            else setOf(SpendingTreatment.AUTO,SpendingTreatment.REVIEW_REQUIRED,SpendingTreatment.FRIEND_REIMBURSEMENT,SpendingTreatment.FUNDING,SpendingTreatment.INCOME,SpendingTreatment.INVESTMENT_RETURN,SpendingTreatment.REFUND,SpendingTreatment.TEMPORARY_MOVEMENT)
        require(treatment in allowed) { "That treatment does not match whether money left or entered the account." }
        if (treatment == SpendingTreatment.FRIEND_REIMBURSEMENT || treatment == SpendingTreatment.REFUND) {
            val original=relatedId?.let(db::get)?:error("Choose the expense this money is paying back.")
            require(original.id != tx.id && original.direction == Direction.DEBIT && original.status == PaymentStatus.POSTED && original.review == ReviewStatus.CONFIRMED && original.transferId == null && SpendingPolicy.treatment(original) == SpendingTreatment.PERSONAL_SPENDING) {
                "Choose a confirmed personal expense to link this payment to."
            }
        }
        var movement: Transaction? = null
        if(treatment == SpendingTreatment.TEMPORARY_MOVEMENT) {
            movement=relatedId?.let(db::get)?:error("Choose the matching transaction for this temporary movement.")
            require(movement.id != tx.id && movement.direction != tx.direction && movement.amountPaise == tx.amountPaise && movement.status == PaymentStatus.POSTED && movement.review == ReviewStatus.CONFIRMED && movement.transferId == null && (movement.relatedTransactionId == null || movement.relatedTransactionId == tx.id)) {
                "Temporary movements must link equal amounts in opposite directions."
            }
        } else require(relatedId == null || treatment == SpendingTreatment.FRIEND_REIMBURSEMENT || treatment == SpendingTreatment.REFUND) {
            "Choose a linked transaction only for a refund, reimbursement, or temporary movement."
        }
        val oldPartner=old?.relatedTransactionId?.let(db::get)
        if(oldPartner?.relatedTransactionId == tx.id && oldPartner.id != movement?.id && SpendingPolicy.treatment(oldPartner) == SpendingTreatment.TEMPORARY_MOVEMENT) {
            db.save(oldPartner.copy(relatedTransactionId=null,spendingTreatment=SpendingTreatment.AUTO,updatedAt=System.currentTimeMillis(),revision=oldPartner.revision+1))
        }
        val resolved=SpendingPolicy.treatment(tx.copy(spendingTreatment=treatment))
        val saved=tx.copy(spendingTreatment=treatment,relatedTransactionId=relatedId,principalPaise=principalPaise,review=if(resolved==SpendingTreatment.REVIEW_REQUIRED)ReviewStatus.DRAFT else ReviewStatus.CONFIRMED,updatedAt=System.currentTimeMillis(),revision=old?.revision?.plus(1)?:tx.revision)
        db.save(saved)
        movement?.let { db.save(it.copy(spendingTreatment=SpendingTreatment.TEMPORARY_MOVEMENT,relatedTransactionId=tx.id,updatedAt=System.currentTimeMillis(),revision=it.revision+1)) }
    }
    suspend fun matchTransfer(aId: String,bId: String) = withContext(Dispatchers.IO) {
        val database=db.writableDatabase;database.beginTransaction()
        try {
            val a=db.get(aId)?:error("Transaction missing");val b=db.get(bId)?:error("Transaction missing")
            require(a.id!=b.id&&a.direction!=b.direction&&a.account!=b.account&&a.account in db.ownAccounts()&&b.account in db.ownAccounts()&&a.amountPaise==b.amountPaise&&a.status==PaymentStatus.POSTED&&b.status==PaymentStatus.POSTED)
            val pair=java.util.UUID.randomUUID().toString()
            require(a.relatedTransactionId==null&&b.relatedTransactionId==null) { "Unlink the existing movement relationship first." }
            listOf(a,b).forEach { db.save(it.copy(transferId=pair,category=Category.TRANSFER,spendingTreatment=SpendingTreatment.AUTO,relatedTransactionId=null,review=ReviewStatus.CONFIRMED,updatedAt=System.currentTimeMillis(),revision=it.revision+1)) }
            database.setTransactionSuccessful();changed()
        } finally { database.endTransaction() }
    }
    suspend fun unmatchTransfer(id: String) = withContext(Dispatchers.IO) {
        val pair=db.get(id)?.transferId?:return@withContext
        val database=db.writableDatabase;database.beginTransaction()
        try { db.transactions().filter { it.transferId==pair }.forEach { db.save(it.copy(transferId=null,category=Category.OTHER,review=ReviewStatus.DRAFT,updatedAt=System.currentTimeMillis(),revision=it.revision+1)) };database.setTransactionSuccessful();changed() }finally{database.endTransaction()}
    }
}
