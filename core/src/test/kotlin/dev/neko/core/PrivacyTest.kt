package dev.neko.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivacyTest {
    @Test fun `secrets are recognised before chat text is stored`() {
        listOf("Remember for next time: my bank OTP is 123456", "my UPI PIN is 1234", "card 4111 1111 1111 1111", "my password is hunter2").forEach { assertTrue(Privacy.containsSecret(it), it) }
        listOf("Remember for next time: Swiggy is food", "how much did I spend on pins and needles?", "ref 612345678901").forEach { assertFalse(Privacy.containsSecret(it), it) }
    }
}
