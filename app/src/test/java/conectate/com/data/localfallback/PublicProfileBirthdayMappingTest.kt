package conectate.com.data.localfallback
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.test.UnitTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class PublicProfileBirthdayMappingTest {
 @Test fun `public profile keeps birthday and presentation fields`() {
  val config=SupabaseConfig(ApplicationProvider.getApplicationContext())
  try {
   val api=LocalFallbackApiClient(config)
   val user=api.mapProfileUser(Json.parseToJsonElement("""{"id":"profile","birth_date":"2000-10-17","education":"Universidad","workplace":"Trabajo","career":"Ingeniería"}""").jsonObject)
   assertEquals("2000-10-17",user.birth_date);assertEquals("Universidad",user.education);assertEquals("Trabajo",user.workplace);assertEquals("Ingeniería",user.career)
  } finally { config.httpClient.close() }
 }
 @Test fun `server redaction and legacy birthday name are respected`() {
  val config=SupabaseConfig(ApplicationProvider.getApplicationContext())
  try {
   val api=LocalFallbackApiClient(config)
   assertNull(api.mapProfileUser(Json.parseToJsonElement("""{"id":"profile","birth_date":""}""").jsonObject).birth_date)
   assertEquals("2000-10-17",api.mapProfileUser(Json.parseToJsonElement("""{"id":"profile","birthDate":"2000-10-17"}""").jsonObject).birth_date)
  } finally { config.httpClient.close() }
 }
}
