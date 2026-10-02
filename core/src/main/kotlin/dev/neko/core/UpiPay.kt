package dev.neko.core

import java.net.URLEncoder

/** Builds `upi://pay` links and reads the answer a UPI app (Google Pay, PhonePe, ...) hands back. */
object UpiPay {
    enum class Status { SUCCESS, FAILURE, SUBMITTED, UNKNOWN }
    /** [reference] is the bank's approval/RRN number, the same one that appears in the "UPI Ref" of the bank SMS. */
    data class Result(val status: Status, val reference: String?)

    private val vpa = Regex("[A-Za-z0-9._\\-]{2,256}@[A-Za-z][A-Za-z0-9\\-]{1,63}")
    fun isValidVpa(text: String): Boolean = vpa.matches(text.trim())

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    private fun clean(text: String, max: Int): String = text.filter { !it.isISOControl() }.trim().take(max)

    /** Fields a merchant's QR code may carry that the payee's bank needs back (merchant category, order reference, ...). Anything else is dropped. */
    private val merchantFields = setOf("mc", "tr", "tid", "url", "mode", "purpose", "orgid", "sign")

    fun link(vpa: String, name: String, amountPaise: Long, note: String, extras: Map<String, String> = emptyMap()): String {
        require(isValidVpa(vpa)) { "Enter a valid UPI ID, like name@bank" }
        require(amountPaise in 1..Budgeting.MAX_PAISE) { "Enter an amount above zero" }
        val payee = clean(name, 50).ifEmpty { vpa.trim() }
        val message = clean(note, 80)
        return "upi://pay?pa=${encode(vpa.trim())}&pn=${encode(payee)}&am=${Money.decimal(amountPaise)}&cu=INR" + (if (message.isEmpty()) "" else "&tn=${encode(message)}") +
            extras.filterKeys { it in merchantFields }.entries.joinToString("") { "&${it.key}=${encode(clean(it.value, 200))}" }
    }

    /** A payment read from a scanned QR code; [amountPaise] is null for an open-amount code, which the user fills in. */
    data class Parsed(val vpa: String, val name: String, val amountPaise: Long?, val note: String, val extras: Map<String, String>)

    /** Reads a `upi://pay` link from a QR code. Anything else (other schemes, mandates, other currencies, invalid IDs) is rejected. */
    fun parseLink(raw: String): Parsed? {
        val text = raw.trim()
        if (!text.startsWith("upi://pay", ignoreCase = true)) return null
        val query = text.substringAfter('?', "")
        if (query.isEmpty()) return null
        val params = try {
            query.split('&').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { java.net.URLDecoder.decode(it[0], "UTF-8").lowercase() to java.net.URLDecoder.decode(it[1], "UTF-8") } }.toMap()
        } catch (_: IllegalArgumentException) { return null }
        val vpa = params["pa"]?.trim() ?: return null
        if (!isValidVpa(vpa)) return null
        if (params["cu"]?.equals("INR", ignoreCase = true) == false) return null
        return Parsed(vpa, clean(params["pn"].orEmpty(), 50), params["am"]?.let { Budgeting.parseRupees(it) }, clean(params["tn"].orEmpty(), 80), params.filterKeys { it in merchantFields })
    }

    /**
     * Whether a bank notice's counterparty is the payee of a payment recorded through Neko. Used when the UPI reference is unavailable,
     * so an unrelated payment of the same amount a few minutes later is not swallowed. [recordedNotes] holds "... UPI to <upi id>".
     */
    fun samePayee(recordedMerchant: String, recordedNotes: String, smsMerchant: String): Boolean {
        val sms = smsMerchant.lowercase()
        if (sms.isBlank() || sms.contains("unknown counterparty")) return false
        val keys = mutableSetOf<String>()
        Regex("UPI to (\\S+@\\S+)").find(recordedNotes)?.groupValues?.get(1)?.lowercase()?.let { keys += it; keys += it.substringBefore('@') }
        keys += recordedMerchant.lowercase().split(Regex("[^a-z0-9]+"))
        return keys.any { it.length >= 4 && sms.contains(it) }
    }

    fun parseResponse(raw: String?): Result {
        val fields = raw.orEmpty().split('&').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim().lowercase() to it[1].trim() } }.toMap()
        val status = when (fields["status"]?.uppercase()) {
            "SUCCESS" -> Status.SUCCESS
            "FAILURE", "FAILED" -> Status.FAILURE
            "SUBMITTED", "PENDING" -> Status.SUBMITTED
            else -> Status.UNKNOWN
        }
        val reference = fields["approvalrefno"]?.uppercase()?.takeIf { Regex("[A-Z0-9]{6,30}").matches(it) }
        return Result(status, reference)
    }
}
