package dev.neko.app.data

import dev.neko.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Placeholder account for payments started in Neko; replaced by the real bank account once the bank SMS is matched. */
const val UPI_APP_ACCOUNT = "UPI app"

class LedgerRepository(val db: NekoDatabase, val settings: SecureSettings) {
    val changes = MutableStateFlow(0L)
    fun changed() { changes.value = changes.value + 1 }
    suspend fun transactions(): List<Transaction> = withContext(Dispatchers.IO) { db.transactions() }
    private fun storeRaw(database: android.database.sqlite.SQLiteDatabase, fingerprint: String, sms: BankSms) {
        database.insertWithOnConflict("raw_sms",null,android.content.ContentValues().apply { put("fingerprint",fingerprint);put("ciphertext",settings.encrypt(JSONObject().put("sender",sms.sender).put("body",sms.body).toString(),"sms:"+fingerprint)) },android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }
    suspend fun capture(sms: BankSms): Transaction? = withContext(Dispatchers.IO) {
        val incoming = SmsParser().parse(sms) ?: return@withContext null
        val database=db.writableDatabase;database.beginTransaction()
        try {
            // A payment recorded through Neko's Pay button adopts its bank notice's fingerprint, so every later notice for it (and every replay) finds it here.
            var old=db.get(incoming.id)?:db.getByFingerprint(incoming.fingerprint)
            if(old==null) {
                // Same money as a payment started in Neko: match by UPI reference, or by amount + time + the payee named in the notice.
                val recorded=db.transactions().firstOrNull { it.source==Source.MANUAL&&it.account==UPI_APP_ACCOUNT&&it.direction==incoming.direction&&it.amountPaise==incoming.amountPaise&&it.status!=PaymentStatus.FAILED&&
                    ((incoming.reference!=null&&it.reference==incoming.reference)||(kotlin.math.abs(it.occurredAt-incoming.occurredAt)<=1_800_000&&UpiPay.samePayee(it.merchant,it.notes,incoming.merchant))) }
                if(recorded!=null) {
                    val merged=recorded.copy(fingerprint=incoming.fingerprint,status=if(SmsLifecycle.advances(recorded.status,incoming.status))incoming.status else recorded.status,account=incoming.account,
                        reference=incoming.reference?:recorded.reference,updatedAt=System.currentTimeMillis(),revision=recorded.revision+1)
                    db.save(merged);storeRaw(database,incoming.fingerprint,sms)
                    database.setTransactionSuccessful();changed();return@withContext null // the user started this payment, so no "new transaction" alert
                }
            }
            val advances=old!=null&&SmsLifecycle.advances(old.status,incoming.status)
            // Replays and out-of-order notices (inbox catch-up re-reads recent messages) never undo or repeat a status change.
            if(old!=null&&!advances&&old.amountPaise==incoming.amountPaise&&old.direction==incoming.direction) { database.setTransactionSuccessful();return@withContext null }
            val conflict=old!=null&&(old.amountPaise!=incoming.amountPaise||old.direction!=incoming.direction)
            val tx=if(old==null)incoming else old.copy(status=if(advances)incoming.status else old.status,review=if(conflict)ReviewStatus.DRAFT else old.review,notes=if(conflict)"Conflicting bank notice. Check encrypted original notices before confirming." else old.notes,updatedAt=System.currentTimeMillis(),revision=old.revision+1)
            db.save(tx);storeRaw(database,tx.fingerprint,sms)
            if(incoming.status==PaymentStatus.REVERSED&&incoming.reference!=null) {
                db.transactions().filter { it.id!=tx.id&&it.account==incoming.account&&it.reference==incoming.reference&&it.amountPaise==incoming.amountPaise&&it.direction!=incoming.direction }.forEach { db.save(it.copy(status=PaymentStatus.REVERSED,updatedAt=System.currentTimeMillis(),revision=it.revision+1)) }
            }
            database.setTransactionSuccessful();changed();tx
        } finally { database.endTransaction() }
    }
    // Payments started with Neko's Pay button whose outcome is not known yet; the newest is the one the UPI app is about to report on.
    private fun pendingUpiIds(): List<String> = settings.get("pending_upi").split(',').filter { it.isNotBlank() }
    private fun addPendingUpi(id: String) = settings.put("pending_upi", (pendingUpiIds() + id).takeLast(5).joinToString(","))
    private fun removePendingUpi(id: String) { val rest = pendingUpiIds() - id; if (rest.isEmpty()) settings.remove("pending_upi") else settings.put("pending_upi", rest.joinToString(",")) }
    fun currentPendingUpi(): String? = pendingUpiIds().lastOrNull()
    /** Records a payment the moment the user taps Pay, before the UPI app opens. It stays PENDING (and out of reports) until the result is known. */
    suspend fun startUpi(vpa: String, name: String, amountPaise: Long, note: String): Transaction = withContext(Dispatchers.IO) {
        require(UpiPay.isValidVpa(vpa)) { "Enter a valid UPI ID, like name@bank" }
        val merchant = name.trim().ifEmpty { vpa.trim() }.take(100)
        val tx = Transaction(occurredAt = System.currentTimeMillis(), amountPaise = amountPaise, direction = Direction.DEBIT, account = UPI_APP_ACCOUNT, merchant = merchant,
            category = LocalClassifier.classify("$merchant $note", Direction.DEBIT), paymentMethod = "UPI", notes = ("Paid with UPI to ${vpa.trim()}" + note.trim().takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()).take(240),
            source = Source.MANUAL, review = ReviewStatus.CONFIRMED, status = PaymentStatus.PENDING)
        db.save(tx);addPendingUpi(tx.id);changed();tx
    }
    /** Settles a payment from [startUpi]. A bank SMS that already arrived for the same UPI reference is merged in rather than counted twice. */
    suspend fun finishUpi(id: String, status: PaymentStatus, reference: String?): Transaction? = withContext(Dispatchers.IO) {
        val database=db.writableDatabase;database.beginTransaction()
        try {
            val tx=db.get(id)
            if(tx==null||tx.status!=PaymentStatus.PENDING) { removePendingUpi(id);database.setTransactionSuccessful();return@withContext tx }
            val duplicate=reference?.let { ref->db.transactions().firstOrNull { it.id!=id&&it.source==Source.SMS&&it.reference==ref&&it.amountPaise==tx.amountPaise&&it.direction==Direction.DEBIT } }
            val settled=tx.copy(status=if(duplicate!=null&&SmsLifecycle.advances(status,duplicate.status))duplicate.status else status,reference=reference?:tx.reference,
                account=duplicate?.account?:tx.account,fingerprint=duplicate?.fingerprint?:tx.fingerprint,updatedAt=System.currentTimeMillis(),revision=tx.revision+1)
            if(duplicate!=null)db.delete(duplicate.id) // remove first: the settled payment takes over its fingerprint, which must stay unique
            db.save(settled)
            removePendingUpi(id)
            database.setTransactionSuccessful()
            changed();settled
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
