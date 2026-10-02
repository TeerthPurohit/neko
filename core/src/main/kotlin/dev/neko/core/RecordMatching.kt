package dev.neko.core

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Decides whether a bank notice or statement row is the same payment as a record Neko already holds. */
object RecordMatching {
    class Candidate<T>(val item: T, val reference: String?, val occurredAt: Long)

    /**
     * Candidates are assumed to have the same amount and direction. Only a shared bank reference proves two records are one payment;
     * amount and date alone could be a second purchase, so without a reference nothing is merged (see [nearby]).
     */
    fun <T> best(candidates: List<Candidate<T>>, reference: String?, occurredAt: Long): Candidate<T>? =
        if (reference == null) null else candidates.firstOrNull { it.reference == reference }

    /**
     * The nearest record on the same or the next calendar day (IST) whose reference does not conflict: possibly the same payment,
     * so the new record is kept as a draft for the user to compare instead of being merged or counted twice.
     */
    fun <T> nearby(candidates: List<Candidate<T>>, reference: String?, occurredAt: Long): Candidate<T>? {
        fun day(millis: Long) = Instant.ofEpochMilli(millis).atZone(Ledger.india).toLocalDate()
        return candidates
            .filter { (reference == null || it.reference == null) && abs(ChronoUnit.DAYS.between(day(it.occurredAt), day(occurredAt))) <= 1 }
            .minByOrNull { abs(it.occurredAt - occurredAt) }
    }
}
