package dev.neko.core

import kotlin.test.*

class UpiPayTest {
    @Test fun `accepts normal handles and rejects malformed ones`() {
        listOf("friend@okicici", "john.doe-1@ybl", "9876543210@paytm", "shop_name@hdfcbank").forEach { assertTrue(UpiPay.isValidVpa(it), it) }
        listOf("", "friend", "@ybl", "a@b", "friend@", "two@@ybl", "has space@ybl", "friend@ybl?am=1", "x@y.z/evil").forEach { assertFalse(UpiPay.isValidVpa(it), "'$it'") }
    }
    @Test fun `builds a upi pay link with exact paise and encoded text`() {
        val link = UpiPay.link("friend@okicici", "Ravi & Sons", 123_450, "Dinner #2")
        assertEquals("upi://pay?pa=friend%40okicici&pn=Ravi%20%26%20Sons&am=1234.50&cu=INR&tn=Dinner%20%232", link)
        assertEquals("upi://pay?pa=ab%40ybl&pn=ab%40ybl&am=10.00&cu=INR", UpiPay.link("ab@ybl", "", 1000, ""))
    }
    @Test fun `parses the response string the payment app returns`() {
        val ok = UpiPay.parseResponse("txnId=AXI123&responseCode=00&ApprovalRefNo=612345678901&Status=SUCCESS&txnRef=TR1")
        assertEquals(UpiPay.Status.SUCCESS, ok.status); assertEquals("612345678901", ok.reference)
        assertEquals(UpiPay.Status.FAILURE, UpiPay.parseResponse("Status=FAILURE&responseCode=U30").status)
        assertEquals(UpiPay.Status.SUBMITTED, UpiPay.parseResponse("status=submitted").status)
        assertEquals(UpiPay.Status.UNKNOWN, UpiPay.parseResponse(null).status)
        assertEquals(UpiPay.Status.UNKNOWN, UpiPay.parseResponse("garbage").status)
        assertNull(UpiPay.parseResponse("Status=SUCCESS&ApprovalRefNo=12").reference, "too short to be a bank reference")
    }
}
