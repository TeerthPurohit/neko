package dev.neko.core

import kotlin.test.*

class TransfersTest {
    private val base = java.time.Instant.parse("2026-10-02T10:00:00Z").toEpochMilli()
    private val own = setOf("ICICI · 1234", "AU · 5678", "IDFC FIRST · 9999")
    private fun tx(id: String, direction: Direction, account: String, minutes: Long = 0, amount: Long = 500_000, merchant: String = "Someone", source: Source = Source.SMS,
                   reference: String? = null, status: PaymentStatus = PaymentStatus.POSTED) =
        Transaction(id = id, occurredAt = base + minutes * 60_000, amountPaise = amount, direction = direction, account = account, merchant = merchant, source = source, reference = reference, status = status)
    private fun pairs(rows: List<Transaction>, name: String = "Teerth Purohit", owned: Set<String> = own, excluded: Set<String> = emptySet()) = Transfers.autoPairs(rows, name, owned, excluded)

    @Test fun `the same bank reference links the legs even hours apart and without known accounts`() {
        val rows = listOf(tx("out", Direction.DEBIT, "ICICI · 1234", reference = "612345678901"), tx("in", Direction.CREDIT, "AU · 5678", minutes = 300, reference = "612345678901"))
        val found = pairs(rows, owned = emptySet())
        assertEquals(1, found.size); assertEquals(setOf("out", "in"), setOf(found[0].first.id, found[0].second.id))
    }
    @Test fun `two bank notices a moment apart need both accounts to be known as yours`() {
        val rows = listOf(tx("out", Direction.DEBIT, "ICICI · 1234"), tx("in", Direction.CREDIT, "AU · 5678", minutes = 1))
        assertEquals(1, pairs(rows).size)
        assertEquals(0, pairs(rows, owned = emptySet()).size, "a friend paying you at the same moment must not look like a transfer")
        assertEquals(0, pairs(rows, owned = setOf("ICICI · 1234")).size, "one known account is not enough")
    }
    @Test fun `coincidences are not linked`() {
        assertEquals(0, pairs(listOf(tx("out", Direction.DEBIT, "ICICI · 1234"), tx("in", Direction.CREDIT, "AU · 5678", minutes = 120))).size, "two hours apart")
        assertEquals(0, pairs(listOf(tx("out", Direction.DEBIT, "ICICI · 1234"), tx("in", Direction.CREDIT, "ICICI · 1234", minutes = 1))).size, "same account is a refund, not a transfer")
        assertEquals(0, pairs(listOf(tx("out", Direction.DEBIT, "ICICI · 1234"), tx("in", Direction.CREDIT, "AU · 5678", minutes = 1, amount = 400_000))).size, "different amounts")
        assertEquals(0, pairs(listOf(tx("out", Direction.DEBIT, "ICICI · 1234"), tx("in", Direction.CREDIT, "AU · 5678", minutes = 1, status = PaymentStatus.PENDING))).size, "not posted yet")
        assertEquals(0, pairs(listOf(tx("out", Direction.DEBIT, "ICICI · unidentified"), tx("in", Direction.CREDIT, "AU · 5678", minutes = 1)), owned = own + "ICICI · unidentified").size, "an unidentified account proves nothing")
    }
    @Test fun `an ambiguous match is left for the user`() {
        val rows = listOf(tx("out", Direction.DEBIT, "ICICI · 1234"), tx("in1", Direction.CREDIT, "AU · 5678", minutes = 1), tx("in2", Direction.CREDIT, "IDFC FIRST · 9999", minutes = 2))
        assertEquals(0, pairs(rows).size)
    }
    @Test fun `your full name on a leg links it but one shared word does not`() {
        val rows = listOf(tx("out", Direction.DEBIT, "ICICI · 1234", merchant = "TEERTH PUROHIT"), tx("in", Direction.CREDIT, "Bank statement", minutes = 600, source = Source.STATEMENT))
        assertEquals(1, pairs(rows, "Teerth Purohit", owned = emptySet()).size)
        assertEquals(0, pairs(rows, "Someone Else", owned = emptySet()).size, "a stranger's name does not")
        assertEquals(0, pairs(rows, "Teerth", owned = emptySet()).size, "a first name alone is too weak to prove anything")
        val friend = listOf(tx("out", Direction.DEBIT, "ICICI · 1234", merchant = "Rahul Verma"), tx("in", Direction.CREDIT, "AU · 5678", minutes = 600, source = Source.STATEMENT))
        assertEquals(0, pairs(friend, "Rahul Sharma", owned = emptySet()).size, "sharing a first name with the payee is not the same person")
        assertTrue(Transfers.looksSelf("Self transfer", "You") && Transfers.looksSelf("To own account", "You"))
        assertFalse(Transfers.looksSelf("Supermarket Mark", "Mark Twain"), "whole words only")
    }
    @Test fun `unlinked by the user stays unlinked`() {
        val rows = listOf(tx("out", Direction.DEBIT, "ICICI · 1234"), tx("in", Direction.CREDIT, "AU · 5678", minutes = 1))
        assertEquals(1, pairs(rows).size)
        assertEquals(0, pairs(rows, excluded = setOf("in")).size)
    }
    @Test fun `entries other rows point at or that are already linked are never paired`() {
        val out = tx("out", Direction.DEBIT, "ICICI · 1234")
        val refund = tx("ref", Direction.CREDIT, "ICICI · 1234", minutes = 600).copy(relatedTransactionId = "out")
        assertEquals(0, pairs(listOf(out, refund, tx("in", Direction.CREDIT, "AU · 5678", minutes = 1))).size, "a refund is linked to this expense")
        assertEquals(0, pairs(listOf(out.copy(transferId = "t"), tx("in", Direction.CREDIT, "AU · 5678", minutes = 1))).size)
        assertEquals(0, pairs(listOf(tx("out", Direction.DEBIT, "UPI app"), tx("in", Direction.CREDIT, "AU · 5678", minutes = 1)), owned = own + "UPI app").size)
    }
    @Test fun `manual marking finds the nearest completed opposite entry in a real account`() {
        val rows = listOf(tx("out", Direction.DEBIT, "ICICI · 1234"), tx("far", Direction.CREDIT, "AU · 5678", minutes = 2000), tx("near", Direction.CREDIT, "IDFC FIRST · 9999", minutes = 30))
        assertEquals("near", Transfers.counterpart(rows[0], rows)?.id)
        assertNull(Transfers.counterpart(rows[0], listOf(rows[0], tx("x", Direction.CREDIT, "AU · 5678", minutes = 60 * 24 * 5))), "five days is too far")
        assertNull(Transfers.counterpart(rows[0], listOf(rows[0], tx("p", Direction.CREDIT, "AU · 5678", minutes = 1, status = PaymentStatus.PENDING))), "pending is not completed")
        assertNull(Transfers.counterpart(rows[0], listOf(rows[0], tx("c", Direction.CREDIT, "Cash", minutes = 1))), "cash is not a bank account")
        assertTrue(Transfers.isIdentified("ICICI · 1234")); assertFalse(Transfers.isIdentified("UPI app")); assertFalse(Transfers.isIdentified("AU · unidentified"))
    }
}
