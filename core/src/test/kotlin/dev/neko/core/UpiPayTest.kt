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
    @Test fun `reads a scanned merchant or personal qr code`() {
        val parsed = UpiPay.parseLink("upi://pay?pa=ravi%40okicici&pn=Ravi%20Kumar&am=250.00&cu=INR&tn=Dinner&mc=5411&tr=T123&junk=1")!!
        assertEquals("ravi@okicici", parsed.vpa); assertEquals("Ravi Kumar", parsed.name); assertEquals(25_000L, parsed.amountPaise); assertEquals("Dinner", parsed.note)
        assertEquals(mapOf("mc" to "5411", "tr" to "T123"), parsed.extras, "only known merchant fields are kept")
        assertNull(UpiPay.parseLink("upi://pay?pa=ravi%40okicici")!!.amountPaise, "an open-amount code leaves the amount to the user")
        assertEquals("shop@ybl", UpiPay.parseLink("UPI://pay?pa=shop@ybl&pn=Shop")!!.vpa)
    }
    @Test fun `rejects links that are not safe upi payments`() {
        listOf("https://example.com/pay?pa=a@ybl", "upi://mandate?pa=ab@ybl", "upi://pay?pn=NoId", "upi://pay?pa=bad", "upi://pay?pa=ab@ybl&cu=USD", "hello").forEach { assertNull(UpiPay.parseLink(it), it) }
    }
    @Test fun `only an exact upi pay path is accepted and long signatures survive`() {
        assertNull(UpiPay.parseLink("upi://payXYZ?pa=ab@ybl"))
        val signature = "A".repeat(400)
        val parsed = UpiPay.parseLink("upi://pay?pa=shop@ybl&pn=Shop&am=10.00&sign=$signature")!!
        assertEquals(signature, parsed.extras["sign"])
        assertTrue(UpiPay.link("shop@ybl", "Shop", 1000, "", parsed.extras).endsWith("&sign=$signature"), "a truncated signature would be rejected by the payee's bank")
    }
    @Test fun `a scanned code keeps its merchant fields when the link is rebuilt`() {
        assertEquals("upi://pay?pa=shop%40ybl&pn=Shop&am=99.50&cu=INR&mc=5411&tr=T9", UpiPay.link("shop@ybl", "Shop", 9_950, "", mapOf("mc" to "5411", "tr" to "T9")))
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
