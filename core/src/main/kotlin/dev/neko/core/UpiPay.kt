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

    fun link(vpa: String, name: String, amountPaise: Long, note: String): String {
        require(isValidVpa(vpa)) { "Enter a valid UPI ID, like name@bank" }
        require(amountPaise in 1..Budgeting.MAX_PAISE) { "Enter an amount above zero" }
        val payee = clean(name, 50).ifEmpty { vpa.trim() }
        val message = clean(note, 80)
        return "upi://pay?pa=${encode(vpa.trim())}&pn=${encode(payee)}&am=${Money.decimal(amountPaise)}&cu=INR" + if (message.isEmpty()) "" else "&tn=${encode(message)}"
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
