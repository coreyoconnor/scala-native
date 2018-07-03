package java.net

/* Written against the specification of HTML 4.01 and RFC 1738
 *
 * https://www.w3.org/TR/html401/interact/forms.html#h-17.13.4.1
 * http://www.ietf.org/rfc/rfc1738.txt
 */

object URLEncoderSuite extends tests.Suite {
  test("null input string") {
    assertThrows[NullPointerException] {
      URLEncoder.encode(null, "ignored")
    }
  }

  test("null encoding name") {
    assertThrows[NullPointerException] {
      URLEncoder.encode("any", null)
    }
  }

  test("early throw of UnsupportedEncodingException") {
    assertThrows[java.io.UnsupportedEncodingException] {
      URLEncoder.encode("any", "invalid encoding name")
    }
  }
}
