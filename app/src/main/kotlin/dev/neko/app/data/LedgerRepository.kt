package dev.neko.app.data

import dev.neko.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Placeholder account for payments started in Neko; replaced by the real bank account once the bank SMS is matched. */
const val UPI_APP_ACCOUNT = "UPI app"
/** Imported statement rows start with this fingerprint prefix until a bank SMS for the same payment claims them. */
const val STATEMENT_PREFIX = "stmt:"
/** Marks a note naming a possible duplicate; such entries are never confirmed automatically. */
const val POSSIBLE_DUPLICATE = "same amount, no shared bank reference"

/** What an import did: rows added, rows that matched something already held, the new spending ready to confirm, and new debits that look like transfers or card bills ([needsReview]). */
data class ImportResult(val imported: Int, val alreadyRecorded: Int, val alreadyImported: Int, val newDebitIds: List<String>, val skipped: Int = 0, val needsReview: Int = 0, val transfersLinked: Int = 0)

class LedgerRepository(val db: NekoDatabase, val settings: SecureSettings) {
    val changes = MutableStateFlow(0L)
    fun changed() { changes.value = changes.value + 1 }
    suspend fun transactions(): List<Transaction> = withContext(Dispatchers.IO) { db.transactions() }
    private fun storeRaw(database: android.database.sqlite.SQLiteDatabase, fingerprint: String, sms: BankSms) {
        database.insertWithOnConflict("raw_sms",null,android.content.ContentValues().apply { put("fingerprint",fingerprint);put("ciphertext",settings.encrypt(JSONObject().put("sender",sms.sender).put("body",sms.body).toString(),"sms:"+fingerprint)) },android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }
    private fun possibleDuplicateNote(prefix: String, other: Transaction): String =
        "$prefix It may be the same payment as ${other.merchant} on ${java.time.Instant.ofEpochMilli(other.occurredAt).atZone(Ledger.india).toLocalDate()} ($POSSIBLE_DUPLICATE). If so, delete one of them."
    /** Records what Neko did on its own in Activity, with what is needed to undo it. */
    private fun logAuto(before: Transaction, after: Transaction, reason: String) {
        val confirmed=after.review==ReviewStatus.CONFIRMED&&before.review!=ReviewStatus.CONFIRMED
        db.saveActivity(JSONObject().put("id","auto:${after.id}").put("kind","auto").put("created_at",System.currentTimeMillis())
            .put("title",if(confirmed)"Neko filed a payment" else "Neko set a category")
            .put("body","${Money.rupees(after.amountPaise)} ${if(after.direction==Direction.DEBIT)"to" else "from"} ${after.merchant}: ${after.category.label}${if(confirmed)", confirmed" else ""}, because $reason.")
            .put("undo",JSONObject().put("transaction_id",after.id).put("revision",after.revision).put("category",before.category.name).put("review",before.review.name)))
    }
    /** Lets Neko file [tx] on its own when it is sure; returns the transaction to save. */
    private fun autopilot(tx: Transaction, possibleDuplicate: Boolean): Transaction {
        if(!settings.autopilot||settings.paused)return tx
        val decision=Autopilot.decide(tx,db.transactions(),possibleDuplicate)?:return tx
        val filed=tx.copy(category=decision.category,review=if(decision.confirm)ReviewStatus.CONFIRMED else tx.review)
        if(filed!=tx)logAuto(tx,filed,decision.reason)
        return filed
    }
    /** Undoes something Neko did on its own (an Activity entry with "undo"), unless the transaction has changed since. */
    suspend fun undoAuto(activityId: String) = withContext(Dispatchers.IO) {
        val database=db.writableDatabase;database.beginTransaction()
        try {
            val entry=db.activity().firstOrNull { it.getString("id")==activityId }?:error("This entry is no longer available")
            val undo=entry.optJSONObject("undo")?:error("This was already undone")
            val tx=db.get(undo.getString("transaction_id"))?:error("This transaction no longer exists")
            require(tx.revision==undo.getInt("revision")){"This transaction has changed since, so open it and edit it instead."}
            db.save(tx.copy(category=Category.valueOf(undo.getString("category")),review=ReviewStatus.valueOf(undo.getString("review")),updatedAt=System.currentTimeMillis(),revision=tx.revision+1))
            entry.remove("undo");entry.put("body",entry.getString("body")+" Undone: it is back in your review list.")
            db.saveActivity(entry)
            database.setTransactionSuccessful();changed()
        } finally { database.endTransaction() }
    }
    /** Applies a cloud category suggestion Neko is confident about to a draft that has not changed since it was suggested. */
    fun applyCloudCategory(id: String, expectedUpdatedAt: Long, category: Category, confidence: Double): Boolean {
        if(!settings.autopilot||settings.paused||confidence<0.85)return false
        val database=db.writableDatabase;database.beginTransaction()
        try {
            val tx=db.get(id)?:return false
            if(tx.updatedAt!=expectedUpdatedAt||tx.review!=ReviewStatus.DRAFT||tx.category!=Category.OTHER)return false
            val history=db.transactions()
            val confirm=Autopilot.decide(tx.copy(category=category),history,tx.notes.contains(POSSIBLE_DUPLICATE))?.confirm==true
            val filed=tx.copy(category=category,review=if(confirm)ReviewStatus.CONFIRMED else tx.review,aiSuggestedCategory=category,aiConfidence=confidence,updatedAt=System.currentTimeMillis(),revision=tx.revision+1)
            db.save(filed);logAuto(tx,filed,"Neko's AI is ${(confidence*100).toInt()}% sure")
            database.setTransactionSuccessful();changed();return true
        } finally { database.endTransaction() }
    }
    suspend fun capture(sms: BankSms): Transaction? = withContext(Dispatchers.IO) {
        var incoming = SmsParser().parse(sms) ?: return@withContext null
        val database=db.writableDatabase;database.beginTransaction()
        try {
            // A payment recorded through Neko's Pay button adopts its bank notice's fingerprint, so every later notice for it (and every replay) finds it here.
            var old=db.get(incoming.id)?:db.getByFingerprint(incoming.fingerprint)
            var possibleDuplicate=false
            if(old==null) {
                // Same money as a payment started in Neko: match by UPI reference, or by amount + time + the payee named in the notice.
                // A statement row not yet claimed by a notice still carries its "stmt:" fingerprint; statements only know the date, so allow 36 hours.
                val sameMoney=db.transactions().filter { it.direction==incoming.direction&&it.amountPaise==incoming.amountPaise&&it.status!=PaymentStatus.FAILED }
                val upiRecorded=sameMoney.firstOrNull { it.source==Source.MANUAL&&it.account==UPI_APP_ACCOUNT&&((incoming.reference!=null&&it.reference==incoming.reference)||(kotlin.math.abs(it.occurredAt-incoming.occurredAt)<=1_800_000&&UpiPay.samePayee(it.merchant,it.notes,incoming.merchant))) }
                // A statement row not yet claimed by a notice still carries its "stmt:" fingerprint. Two different references are two payments.
                val statementRows=sameMoney.filter { it.source==Source.STATEMENT&&it.fingerprint.startsWith(STATEMENT_PREFIX) }.map { RecordMatching.Candidate(it,it.reference,it.occurredAt) }
                val recorded=upiRecorded?:RecordMatching.best(statementRows,incoming.reference,incoming.occurredAt)?.item
                // Same amount on a nearby day is not proof of the same payment: the notice stays a separate draft that names the possible duplicate.
                if(recorded==null)RecordMatching.nearby(statementRows,incoming.reference,incoming.occurredAt)?.item?.let { incoming=incoming.copy(notes=possibleDuplicateNote(incoming.notes,it));possibleDuplicate=true }
                if(recorded!=null) {
                    val merged=recorded.copy(fingerprint=incoming.fingerprint,status=if(SmsLifecycle.advances(recorded.status,incoming.status))incoming.status else recorded.status,account=incoming.account,
                        reference=incoming.reference?:recorded.reference,updatedAt=System.currentTimeMillis(),revision=recorded.revision+1)
                    db.save(merged);storeRaw(database,incoming.fingerprint,sms)
                    autoLinkTransfers()
                    database.setTransactionSuccessful();changed();return@withContext null // the user started this payment, so no "new transaction" alert
                }
            }
            val advances=old!=null&&SmsLifecycle.advances(old.status,incoming.status)
            // Replays and out-of-order notices (inbox catch-up re-reads recent messages) never undo or repeat a status change.
            if(old!=null&&!advances&&old.amountPaise==incoming.amountPaise&&old.direction==incoming.direction) { database.setTransactionSuccessful();return@withContext null }
            val conflict=old!=null&&(old.amountPaise!=incoming.amountPaise||old.direction!=incoming.direction)
            var tx=if(old==null)incoming else old.copy(status=if(advances)incoming.status else old.status,review=if(conflict)ReviewStatus.DRAFT else old.review,notes=if(conflict)"Conflicting bank notice. Check encrypted original notices before confirming." else old.notes,updatedAt=System.currentTimeMillis(),revision=old.revision+1)
            // A new notice, or the posted notice completing a pending one, is filed on its own when Neko is sure.
            if(!conflict&&(old==null||advances))tx=autopilot(tx,possibleDuplicate)
            db.save(tx);storeRaw(database,tx.fingerprint,sms)
            if(incoming.status==PaymentStatus.REVERSED&&incoming.reference!=null) {
                db.transactions().filter { it.id!=tx.id&&it.account==incoming.account&&it.reference==incoming.reference&&it.amountPaise==incoming.amountPaise&&it.direction!=incoming.direction }.forEach { db.save(it.copy(status=PaymentStatus.REVERSED,updatedAt=System.currentTimeMillis(),revision=it.revision+1)) }
            }
            // Money moved between the user's own accounts is recognised here, so it never becomes a "review this" draft or counts as spending.
            val linked=autoLinkTransfers()
            database.setTransactionSuccessful();changed()
            if(tx.id in linked)null else tx
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
    /**
     * Adds statement rows to the ledger as drafts. A row that is the same payment as something already held (same reference, or no conflicting
     * reference and the same or the next day) is matched one-to-one instead of duplicated, and importing the same file again adds nothing.
     */
    /** "account|yyyy-MM" for every month a statement was imported for, separated by newlines (account names can hold commas). */
    fun reconciledMonths(): Set<String> = settings.get("statement_months").split('\n').filter { it.isNotBlank() }.toSet()
    private fun markReconciled(marks: Set<String>) { if (marks.isNotEmpty()) settings.put("statement_months", (reconciledMonths() + marks).joinToString("\n")) }
    suspend fun importStatement(rows: List<StatementRow>, account: String, skipped: Int = 0): ImportResult = withContext(Dispatchers.IO) {
        val database=db.writableDatabase;database.beginTransaction()
        try {
            val existing=db.transactions();val claimed=HashSet<String>();val seen=HashMap<String,Int>()
            var imported=0;var already=0;var again=0;var review=0;val debits=ArrayList<String>();val marks=HashSet<String>()
            for(row in rows.sortedBy { it.date }) {
                val month=java.time.YearMonth.from(row.date);marks+="$account|$month"
                val at=row.date.atTime(12,0).atZone(Ledger.india).toInstant().toEpochMilli()
                // Identical rows on one day (two ₹50 teas) stay distinct through their occurrence number. The merchant, not the full narration,
                // keys rows without a reference so page footers glued onto a narration cannot change a row's identity.
                val key="${row.date}|${row.amountPaise}|${row.direction}|${row.reference?:row.merchant.lowercase().take(30)}"
                val number=(seen[key]?:0)+1;seen[key]=number
                val id=STATEMENT_PREFIX+sha256("$account|$key|$number")
                if(db.get(id)!=null){again++;continue}
                val candidates=existing.filter { it.id !in claimed&&it.direction==row.direction&&it.amountPaise==row.amountPaise&&it.status!=PaymentStatus.FAILED }.map { RecordMatching.Candidate(it,it.reference,it.occurredAt) }
                val match=RecordMatching.best(candidates,row.reference,at)?.item
                if(match!=null) {
                    claimed+=match.id;already++;marks+="${match.account}|$month"
                    // The same bank reference on the statement is proof that a pending payment went through.
                    if(match.status==PaymentStatus.PENDING)db.save(match.copy(status=PaymentStatus.POSTED,updatedAt=System.currentTimeMillis(),revision=match.revision+1))
                    continue
                }
                // Same amount on a nearby day without a shared reference may be the same payment or a second one: keep the row as a draft to compare.
                val possible=RecordMatching.nearby(candidates,row.reference,at)?.item
                db.save(Transaction(id=id,fingerprint=id,occurredAt=at,amountPaise=row.amountPaise,direction=row.direction,account=account,merchant=row.merchant.take(100),
                    category=if(row.direction==Direction.CREDIT)Category.OTHER else LocalClassifier.classify(row.merchant,row.direction),paymentMethod=if(row.description.startsWith("UPI",true))"UPI" else "Bank",
                    notes=possible?.let { possibleDuplicateNote("Imported from a bank statement.",it) }?:"Imported from a bank statement. Confirm the details.",confidence=0.6,source=Source.STATEMENT,review=ReviewStatus.DRAFT,status=PaymentStatus.POSTED,reference=row.reference))
                imported++
                if(row.direction==Direction.DEBIT) { if(possible!=null||StatementParser.looksLikeMoneyMovement(row.merchant+" "+row.description))review++ else debits+=id }
            }
            val linked=autoLinkTransfers();debits.removeAll(linked)
            markReconciled(marks)
            database.setTransactionSuccessful();changed()
            ImportResult(imported,already,again,debits,skipped,review,linked.size/2)
        } finally { database.endTransaction() }
    }
    /**
     * Confirms freshly imported spending in one step. Money received stays a draft (income, refund or repayment needs a person), and so do
     * transfers, card bills and investments, which would otherwise inflate spending. Only payments that are still posted are confirmed.
     */
    suspend fun confirmImportedSpending(ids: List<String>): Int = withContext(Dispatchers.IO) {
        val database=db.writableDatabase;database.beginTransaction()
        try {
            var confirmed=0
            for(id in ids) {
                val tx=db.get(id)?:continue
                if(tx.direction==Direction.DEBIT&&tx.review==ReviewStatus.DRAFT&&tx.status==PaymentStatus.POSTED&&!StatementParser.looksLikeMoneyMovement(tx.merchant)) {
                    db.save(tx.copy(review=ReviewStatus.CONFIRMED,updatedAt=System.currentTimeMillis(),revision=tx.revision+1));confirmed++
                }
            }
            database.setTransactionSuccessful();if(confirmed>0)changed();confirmed
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
            val edited=tx.copy(category=category,merchant=merchant,notes=notes,amountPaise=amount,occurredAt=occurredAt,status=status,personalSharePaise=tx.personalSharePaise?.coerceAtMost(amount))
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
            // The user is confirming these two entries, so the accounts do not have to be marked as theirs beforehand; confirming marks them.
            require(a.id!=b.id&&a.direction!=b.direction&&a.account!=b.account&&a.amountPaise==b.amountPaise) { "A transfer needs one payment out and one payment in, for the same amount, in two different accounts." }
            require(a.status==PaymentStatus.POSTED&&b.status==PaymentStatus.POSTED&&a.transferId==null&&b.transferId==null) { "Both payments must be completed and not already part of a transfer." }
            require(a.relatedTransactionId==null&&b.relatedTransactionId==null) { "Unlink the existing movement relationship first." }
            requireNoOtherLinks(a);requireNoOtherLinks(b)
            linkTransfer(a,b)
            database.setTransactionSuccessful();changed()
        } finally { database.endTransaction() }
    }
    /** A refund or reimbursement points at the expense it repays; turning that expense into a transfer would leave those links dangling. A reciprocal temporary-movement partner is fine (it is released). */
    private fun requireNoOtherLinks(tx: Transaction) {
        val blockers=db.transactions().filter { it.relatedTransactionId==tx.id&&tx.relatedTransactionId!=it.id }
        require(blockers.isEmpty()) { "Other entries (a refund or reimbursement) are linked to this one. Unlink them first." }
    }
    /** Marks entries as one transfer between the user's own accounts: out of spending and income, and both accounts remembered as theirs. */
    private fun linkTransfer(vararg legs: Transaction) {
        val pair=java.util.UUID.randomUUID().toString();val now=System.currentTimeMillis()
        legs.forEach { db.save(it.copy(transferId=pair,category=Category.TRANSFER,spendingTreatment=SpendingTreatment.AUTO,relatedTransactionId=null,review=ReviewStatus.CONFIRMED,updatedAt=now,revision=it.revision+1))
            // Only a real bank account is remembered as the user's; "Cash" or the "UPI app" placeholder must not make every such entry look owned.
            if(Transfers.isIdentified(it.account))db.setOwned(it.account,true) }
    }
    private fun unlinkedIds(): Set<String> = settings.get("unlinked_transfers").split(',').filter { it.isNotBlank() }.toSet()
    private fun rememberUnlinked(ids: List<String>) = settings.put("unlinked_transfers",(unlinkedIds().toList()+ids).takeLast(300).joinToString(","))
    /** Links the obvious own-account transfers (see [Transfers.autoPairs]) and returns the ids it linked. */
    private fun autoLinkTransfers(): Set<String> {
        val ids=HashSet<String>()
        for((out,into) in Transfers.autoPairs(db.transactions(),settings.get("user_name"),db.ownAccounts(),unlinkedIds())) { linkTransfer(out,into);ids+=out.id;ids+=into.id }
        return ids
    }
    /** The user says this entry is a transfer between their own accounts. Pairs it with the matching entry when one exists; otherwise it stands alone, excluded from spending and income. */
    suspend fun markOwnTransfer(id: String): Boolean = withContext(Dispatchers.IO) {
        val database=db.writableDatabase;database.beginTransaction()
        try {
            val tx=db.get(id)?:error("Transaction no longer exists")
            require(tx.transferId==null) { "This is already marked as a transfer." }
            require(tx.status==PaymentStatus.POSTED) { "Only a completed payment can be marked as a transfer. Try again once it has gone through." }
            requireNoOtherLinks(tx)
            // A movement link would otherwise be left dangling on its partner.
            tx.relatedTransactionId?.let(db::get)?.takeIf { it.relatedTransactionId==tx.id }?.let { db.save(it.copy(relatedTransactionId=null,spendingTreatment=SpendingTreatment.AUTO,updatedAt=System.currentTimeMillis(),revision=it.revision+1)) }
            val other=Transfers.counterpart(tx.copy(relatedTransactionId=null),db.transactions())
            linkTransfer(*listOfNotNull(tx,other).toTypedArray())
            database.setTransactionSuccessful();changed();other!=null
        } finally { database.endTransaction() }
    }
    suspend fun unmatchTransfer(id: String) = withContext(Dispatchers.IO) {
        val pair=db.get(id)?.transferId?:return@withContext
        val database=db.writableDatabase;database.beginTransaction()
        try {
            // Remember the user's decision so automatic linking never ties these entries together again.
            rememberUnlinked(db.transactions().filter { it.transferId==pair }.map { it.id })
            db.transactions().filter { it.transferId==pair }.forEach { db.save(it.copy(transferId=null,category=Category.OTHER,review=ReviewStatus.DRAFT,updatedAt=System.currentTimeMillis(),revision=it.revision+1)) };database.setTransactionSuccessful();changed() }finally{database.endTransaction()}
    }
}
