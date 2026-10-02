package dev.neko.core

/** Bank notices for one payment can be delivered, re-read and replayed out of order; a payment's status may only move forward. */
object SmsLifecycle {
    private fun rank(status: PaymentStatus): Int = when (status) { PaymentStatus.PENDING -> 0; PaymentStatus.POSTED -> 1; PaymentStatus.FAILED, PaymentStatus.REVERSED -> 2 }
    fun advances(from: PaymentStatus, to: PaymentStatus): Boolean = rank(to) > rank(from)
}
