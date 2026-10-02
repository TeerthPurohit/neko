package dev.neko.core

/** Secrets Neko must never store or send: OTPs, PINs, CVVs, passwords and full card numbers. Mirrors `rejectSensitive` in the backend. */
object Privacy {
    private val secretWords = Regex("""\b(otp|one[\s-]?time\s+pass(word|code)|m?pin|cvv|cvc|pass(word|code))\b""", RegexOption.IGNORE_CASE)
    private val cardNumber = Regex("""\b(?:\d[ -]?){13,19}\b""")
    fun containsSecret(text: String): Boolean = secretWords.containsMatchIn(text) || cardNumber.containsMatchIn(text)
}
