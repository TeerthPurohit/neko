package dev.neko.core

import kotlin.test.*

class SmsLifecycleTest {
    @Test fun `a payment only moves forward through its notices`() {
        assertTrue(SmsLifecycle.advances(PaymentStatus.PENDING, PaymentStatus.POSTED))
        assertTrue(SmsLifecycle.advances(PaymentStatus.POSTED, PaymentStatus.REVERSED))
        assertTrue(SmsLifecycle.advances(PaymentStatus.PENDING, PaymentStatus.FAILED))
        assertFalse(SmsLifecycle.advances(PaymentStatus.POSTED, PaymentStatus.PENDING), "a replayed pending notice must not undo a posted payment")
        assertFalse(SmsLifecycle.advances(PaymentStatus.POSTED, PaymentStatus.POSTED))
        assertFalse(SmsLifecycle.advances(PaymentStatus.FAILED, PaymentStatus.POSTED))
        assertFalse(SmsLifecycle.advances(PaymentStatus.REVERSED, PaymentStatus.POSTED))
    }
    @Test fun `same amount nearby is only the same payee when the notice names them`() {
        assertTrue(UpiPay.samePayee("Ravi Kumar", "Paid with UPI to ravi.k@okicici", "RAVI KUMAR"))
        assertTrue(UpiPay.samePayee("Ravi Kumar", "Paid with UPI to ravik@ybl", "ravik@ybl"))
        assertTrue(UpiPay.samePayee("ravik@ybl", "Paid with UPI to ravik@ybl · rent", "ravik@ybl"))
        assertFalse(UpiPay.samePayee("Ravi Kumar", "Paid with UPI to ravik@ybl", "SWIGGY"), "a different merchant with the same amount is a different payment")
        assertFalse(UpiPay.samePayee("Ravi Kumar", "Paid with UPI to ravik@ybl", "Unknown counterparty"), "an unnamed notice cannot be proven to match")
        assertFalse(UpiPay.samePayee("Al", "Paid with UPI to al@ybl", "ALEX STORES"), "very short names are too weak to match on")
    }
}
