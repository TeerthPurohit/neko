package dev.neko.core

data class BankSms(val sender: String, val body: String, val receivedAt: Long)
interface BankParser { val bank: String; fun accepts(sender: String): Boolean; fun parse(sms: BankSms): Transaction? }

class IndianBankParser(override val bank: String, private val senderCodes: Set<String>) : BankParser {
    // Registered headers look like "JD-ICICIT-S": operator prefix, header, then a TRAI category suffix (S/T/P/G).
    // Match the header token exactly; the legacy compact form ("VMICICIB") is still accepted when it carries no suffix.
    override fun accepts(sender: String): Boolean {
        val tokens = sender.uppercase().split('-', ' ').map { it.replace(Regex("[^A-Z0-9]"), "") }.filter { it.isNotEmpty() }
        val compact = tokens.joinToString("")
        return senderCodes.any { code -> tokens.contains(code) || (tokens.size == 1 && compact.length <= code.length + 2 && compact.endsWith(code)) }
    }
    override fun parse(sms: BankSms): Transaction? {
        if (!accepts(sms.sender)) return null
        val body = sms.body.replace(Regex("\\s+"), " ").trim()
        // A security footer ("Do not share your OTP") is not an OTP message; drop such sentences before deciding.
        val withoutFooter = body.split(Regex("(?<=[.!])\\s+")).filterNot { Regex("\\b(do not|don't|never)\\s+share\\b|\\bnever asks?\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }.joinToString(" ")
        if (Regex("\\b(OTP|one.time.password|verification code)\\b", RegexOption.IGNORE_CASE).containsMatchIn(withoutFooter)) return null
        // Hindi words carry no \b boundary in Java regex (Devanagari is not \w), so they are matched as plain substrings.
        val debit = Regex("\\b(debited|spent|paid|withdrawn|sent|purchase|payment of)\\b|डेबिट|निकाल|भुगतान|खर्च", RegexOption.IGNORE_CASE).containsMatchIn(body)
        val credit = Regex("\\b(credited|received|refund|refunded|reversed|reversal)\\b|क्रेडिट|जमा|प्राप्त", RegexOption.IGNORE_CASE).containsMatchIn(body)
        // A known bank's notice in a script Neko cannot read is kept as a low-confidence draft rather than silently dropped.
        // English bodies with no transaction verb (balance alerts, offers) are still ignored.
        val unreadable = !debit && !credit && Regex("[\\p{L}&&[^\\p{IsLatin}]]").containsMatchIn(body)
        if (!debit && !credit && !unreadable) return null
        // Ignore balance-only notices and promotional messages; amount is selected near a transaction verb.
        val amountPattern = Regex("(?:INR|Rs\\.?|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
        val amounts = amountPattern.findAll(body).toList()
        val amountMatch = amounts.firstOrNull { m ->
            val preceding = body.substring(maxOf(0, m.range.first - 30), m.range.first)
            !Regex("(?:bal(?:ance)?|available|avl)[:. ]*$", RegexOption.IGNORE_CASE).containsMatchIn(preceding)
        } ?: return null
        val amount = try { Money.parse(amountMatch.groupValues[1]) } catch (_: Exception) { return null }
        val accountMatch = Regex("(?:a/c|acct?|account|card)(?:\\s*(?:no\\.?|ending(?: in)?|xx|[*x]+))?\\s*[:.-]?\\s*([xX*]*[0-9]{2,6})", RegexOption.IGNORE_CASE).find(body)
        val account = accountMatch?.groupValues?.get(1)?.filter { it.isDigit() }?.takeLast(4)
        val ref = Regex("(?:UPI\\s*Ref(?:erence)?(?:\\s*No\\.?)?|Ref(?:erence)?(?:\\s*(?:No|ID)\\.?)?|UTR|RRN|Txn(?:\\s*ID)?)\\s*[:#.-]?\\s*([A-Za-z0-9]{6,30})", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.uppercase()
        val merchant = Regex("(?:\\bto\\b|\\bat\\b|\\bfrom\\b)\\s+(.+?)(?=\\s+(?:on|via|using|UPI|Ref|UTR|RRN|Avl|Bal|available|Info)\\b|[.;]|$)", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.trim()?.take(100) ?: "Unknown counterparty"
        val status = when {
            Regex("\\b(reversed|reversal)\\b", RegexOption.IGNORE_CASE).containsMatchIn(body) -> PaymentStatus.REVERSED
            Regex("\\b(failed|declined|unsuccessful)\\b", RegexOption.IGNORE_CASE).containsMatchIn(body) -> PaymentStatus.FAILED
            Regex("\\b(pending|processing|initiated)\\b", RegexOption.IGNORE_CASE).containsMatchIn(body) -> PaymentStatus.PENDING
            else -> PaymentStatus.POSTED
        }
        val refund = Regex("\\b(refund(?:ed)?|reversed|reversal)\\b", RegexOption.IGNORE_CASE).containsMatchIn(body)
        val direction = if (refund || (credit && !debit)) Direction.CREDIT else Direction.DEBIT
        val method = when { body.contains("UPI", true) -> "UPI"; body.contains("NEFT", true) -> "NEFT"; body.contains("IMPS", true) -> "IMPS"; body.contains("card", true) -> "Card"; body.contains("ATM", true) -> "ATM"; else -> "Bank" }
        val normalized = body.lowercase().replace(Regex("\\s+"), " ")
        // A bank reference identifies the payment across its pending/posted notices. Without one, the same text at another time is another
        // payment, so the timestamp is part of the identity; replays of one SMS carry the same timestamp (see SmsInbox).
        val fingerprint = when {
            ref != null && account != null -> sha256("$bank:$account:$ref:$direction")
            ref != null -> sha256("$bank:$ref:$direction")
            else -> sha256("$bank:$normalized:${sms.receivedAt}")
        }
        return Transaction(id = fingerprint, occurredAt = sms.receivedAt, amountPaise = amount, direction = direction,
            account = "$bank · ${account ?: "unidentified"}", merchant = merchant,
            category = if (refund) Category.REFUND else LocalClassifier.classify(merchant, direction), paymentMethod = method,
            confidence = if (unreadable) 0.30 else if (account != null && ref != null && merchant != "Unknown counterparty") 0.92 else 0.60,
            source = Source.SMS, review = ReviewStatus.DRAFT, status = status, reference = ref, fingerprint = fingerprint,
            notes = if (unreadable) "Neko could not read whether this money left or arrived. Check the original notice and fix the direction before confirming."
                else "Captured at SMS receipt time. Confirm the transaction date if the notice was delayed.")
    }
}

class SmsParser(private val parsers: List<BankParser> = listOf(
    IndianBankParser("ICICI", setOf("ICICIB", "ICICIT", "ICICIS")),
    IndianBankParser("IDFC FIRST", setOf("IDFCFB", "IDFCBK", "IDFCFT")),
    IndianBankParser("AU", setOf("AUBANK", "AUSFBK", "AUSFBL")),
    IndianBankParser("HDFC", setOf("HDFCBK", "HDFCBN", "HDFCBT")),
    IndianBankParser("SBI", setOf("SBIINB", "SBIUPI", "SBIPSG", "SBICRD", "ATMSBI", "SBMSBI")),
    IndianBankParser("Axis", setOf("AXISBK", "AXISBN", "AXISCR")),
    IndianBankParser("Kotak", setOf("KOTAKB", "KOTAKBK", "KOTAKM")),
)) {
    fun parse(sms: BankSms): Transaction? = parsers.firstNotNullOfOrNull { it.parse(sms) }
}

object LocalClassifier {
    fun classify(merchant: String, direction: Direction): Category {
        if (direction == Direction.CREDIT) return Category.OTHER
        val rules = mapOf(
            Category.FOOD to "swiggy|zomato|cafe|coffee|restaurant|biryani",
            Category.GROCERIES to "bigbasket|blinkit|zepto|grocery|grocer|dmart",
            Category.TRANSPORT to "uber|ola|metro|irctc|petrol|fuel|rapido",
            Category.SHOPPING to "amazon|flipkart|myntra|ajio",
            Category.BILLS to "electric|broadband|airtel|jio|recharge|utility",
            Category.HEALTH to "pharmacy|hospital|apollo|medical",
            Category.ENTERTAINMENT to "netflix|spotify|cinema|bookmyshow",
        )
        return rules.entries.firstOrNull { Regex(it.value, RegexOption.IGNORE_CASE).containsMatchIn(merchant) }?.key ?: Category.OTHER
    }
}
