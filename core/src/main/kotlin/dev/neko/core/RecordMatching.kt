package dev.neko.core

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Decides whether a bank notice or statement row is the same payment as a record Neko already holds. */
object RecordMatching {
    class Candidate<T>(val item: T, val reference: String?, val occurredAt: Long)

    /**
     * Candidates are assumed to have the same amount and direction. A matching reference wins; two different references never match;
     * otherwise the nearest record on the same or the next calendar day (IST) is chosen, since statements only carry the date.
     */
    fun <T> best(candidates: List<Candidate<T>>, reference: String?, occurredAt: Long): Candidate<T>? {
        if (reference != null) candidates.firstOrNull { it.reference == reference }?.let { return it }
        fun day(millis: Long) = Instant.ofEpochMilli(millis).atZone(Ledger.india).toLocalDate()
        return candidates
            .filter { (reference == null || it.reference == null) && abs(ChronoUnit.DAYS.between(day(it.occurredAt), day(occurredAt))) <= 1 }
            .minByOrNull { abs(it.occurredAt - occurredAt) }
    }
}
