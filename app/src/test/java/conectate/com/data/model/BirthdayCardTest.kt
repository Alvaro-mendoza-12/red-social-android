package conectate.com.data.model
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
class BirthdayCardTest {
 @Test fun `wire format includes defaults required by server`() {
  val card=BirthdayCard(); val wire=Json.parseToJsonElement(card.encode()).jsonObject
  assertEquals("1",wire["version"]!!.jsonPrimitive.content)
  assertEquals("birthday",wire["kind"]!!.jsonPrimitive.content)
  assertEquals("confetti",wire["theme"]!!.jsonPrimitive.content)
  assertEquals(card,BirthdayCard.decode(card.encode()))
 }
 @Test fun `letter rejects unsupported types and limits Unicode codepoints`() {
  assertNull(BirthdayCard.decode("bad"));assertNull(BirthdayCard.decode("null"))
  assertFalse(BirthdayCard(kind="html").isValid);assertFalse(BirthdayCard(theme="other").isValid)
  assertFalse(BirthdayCard(message=" ").isValid)
  assertTrue(BirthdayCard(message="😀".repeat(600)).isValid)
  assertFalse(BirthdayCard(message="😀".repeat(601)).isValid)
 }
}
