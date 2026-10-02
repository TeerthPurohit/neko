package dev.neko.core

import kotlin.test.*

class TransfersTest {
    private val base = java.time.Instant.parse("2026-10-02T10:00:00Z").toEpochMilli()
    private fun tx(id: String, direction: Direction, account: String, minutes: Long = 0, amount: Long = 500_000, merchant: String = "Someone", source: Source = Source.SMS,
                   reference: String? = null, status: PaymentStatus = PaymentStatus.POSTED) =
        Transaction(id = id, occurredAt = base + minutes * 60_000, amountPaise = amount, direction = direction, account = account, merchant = merchant, source = source, reference = reference, status = status)

    @Test fun `two bank notices a moment apart for one amount are a transfer between own accounts`() {
        val rows = listOf(tx("out", Direction.DEBIT, "ICICI · 1234"), tx("in", Direction.CREDIT, "AU · 5678", minutes = 1))
        val pairs = Transfers.autoPairs(rows, "Teerth")
        assertEquals(1, pairs.size); assertEquals(setOf("out", "in"), setOf(pairs[0].first.id, pairs[0].second.id))
    }
    @Test fun `the same bank reference links the legs even hours apart`() {
        val rows = listOf(tx("out", Direction.DEBIT, "ICICI · 1234", reference = "612345678901"), tx("in", Direction.CREDIT, "AU · 5678", minutes = 300, reference = "612345678901"))
        assertEquals(1, Transfers.autoPairs(rows, "Teerth").size)
    }
    @Test fun `coincidences are not linked`() {
        assertEquals(0, Transfers.autoPairs(listOf(tx("out", Direction.DEBIT, "ICICI · 1234"), tx("in", Direction.CREDIT, "AU · 5678", minutes = 120)), "Teerth").size, "two hours apart")
        assertEquals(0, Transfers.autoPairs(listOf(tx("out", Direction.DEBIT, "ICICI · 1234"), tx("in", Direction.CREDIT, "ICICI · 1234", minutes = 1)), "Teerth").size, "same account is a refund, not a transfer")
        assertEquals(0, Transfers.autoPairs(listOf(tx("out", Direction.DEBIT, "ICICI · 1234"), tx("in", Direction.CREDIT, "AU · 5678", minutes = 1, amount = 400_000)), "Teerth").size, "different amounts")
        assertEquals(0, Transfers.autoPairs(listOf(tx("out", Direction.DEBIT, "ICICI · 1234"), tx("in", Direction.CREDIT, "AU · 5678", minutes = 1, status = PaymentStatus.PENDING)), "Teerth").size, "not posted yet")
        assertEquals(0, Transfers.autoPairs(listOf(tx("out", Direction.DEBIT, "ICICI · unidentified"), tx("in", Direction.CREDIT, "AU · 5678", minutes = 1)), "Teerth").size, "an unidentified account proves nothing")
    }
    @Test fun `an ambiguous match is left for the user`() {
        val rows = listOf(tx("out", Direction.DEBIT, "ICICI · 1234"), tx("in1", Direction.CREDIT, "AU · 5678", minutes = 1), tx("in2", Direction.CREDIT, "IDFC FIRST · 9999", minutes = 2))
        assertEquals(0, Transfers.autoPairs(rows, "Teerth").size)
    }
    @Test fun `your own name or the word self in the narration links a statement leg`() {
        val rows = listOf(tx("out", Direction.DEBIT, "ICICI · 1234", merchant = "TEERTH PUROHIT"), tx("in", Direction.CREDIT, "Bank statement", minutes = 600, source = Source.STATEMENT))
        assertEquals(1, Transfers.autoPairs(rows, "Teerth Purohit").size)
        assertEquals(0, Transfers.autoPairs(rows, "Someone Else").size, "a stranger's name does not")
        assertTrue(Transfers.looksSelf("Self transfer", "You") && Transfers.looksSelf("To own account", "You"))
    }
    @Test fun `already linked or placeholder accounts are never paired`() {
        val linked = tx("out", Direction.DEBIT, "ICICI · 1234").copy(transferId = "t")
        assertEquals(0, Transfers.autoPairs(listOf(linked, tx("in", Direction.CREDIT, "AU · 5678", minutes = 1)), "x").size)
        assertEquals(0, Transfers.autoPairs(listOf(tx("out", Direction.DEBIT, "UPI app"), tx("in", Direction.CREDIT, "AU · 5678", minutes = 1)), "x").size)
    }
    @Test fun `manual marking finds the nearest opposite entry without needing owned accounts`() {
        val rows = listOf(tx("out", Direction.DEBIT, "ICICI · 1234"), tx("far", Direction.CREDIT, "AU · 5678", minutes = 2000), tx("near", Direction.CREDIT, "IDFC FIRST · 9999", minutes = 30))
        assertEquals("near", Transfers.counterpart(rows[0], rows)?.id)
        assertNull(Transfers.counterpart(rows[0], listOf(rows[0], tx("x", Direction.CREDIT, "AU · 5678", minutes = 60 * 24 * 5))), "five days is too far")
    }
}
