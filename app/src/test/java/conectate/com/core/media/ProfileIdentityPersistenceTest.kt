package conectate.com.core.media

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileIdentityPersistenceTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var bitmap: Bitmap
    private val source = "https://old.example.invalid/storage/v1/object/public/avatars/person/image.png"

    @Before fun setup() {
        root = Files.createTempDirectory("novvin-identity-persistence-test-").toFile()
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        bitmap = Bitmap.createBitmap(1024, 2048, Bitmap.Config.ARGB_8888)
    }

    @After fun cleanup() {
        bitmap.recycle()
        assertTrue(root.name.startsWith("novvin-identity-persistence-test-"))
        assertTrue(root.deleteRecursively())
    }

    @Test fun `actual encoded avatar is bounded decodable and outside the evictable cache`() = runBlocking {
        val saved = ProfileIdentityImageStore.save(context, source, bitmap, "a")!!
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(saved.path, options)
        assertEquals(256, options.outWidth)
        assertEquals(512, options.outHeight)
        assertEquals("image/webp", options.outMimeType)
        assertTrue(saved.canonicalPath.startsWith(root.canonicalPath + File.separator))
        assertTrue(saved.length() in 1..ProfileIdentityFiles.MAX_ENTRY_BYTES.toLong())
        assertFalse(bitmap.isRecycled)
    }

    @Test fun `saved image resolves from files after endpoint rotation without a network request`() = runBlocking {
        val saved = ProfileIdentityImageStore.save(context, source, bitmap, "a")!!
        val rotated = source.replace("old.example.invalid", "new.example.invalid")
        assertEquals(saved, ProfileIdentityImageStore.find(context, rotated, " A "))
        assertNull(ProfileIdentityImageStore.find(context, rotated, "b"))
    }

    @Test fun `late save captured before clear cannot resurrect an image`() = runBlocking {
        val generation = ProfileIdentityImageStore.generation("a")
        ProfileIdentityImageStore.save(context, source, bitmap, "a", generation)
        val usage = ProfileIdentityImageStore.usage(context, "a")!!
        assertTrue(ProfileIdentityImageStore.clear(context, "a", usage.accountKey))
        assertNull(ProfileIdentityImageStore.save(context, source, bitmap, "a", generation))
        assertNull(ProfileIdentityImageStore.find(context, source, "a"))
        assertNotNull(ProfileIdentityImageStore.save(context, source, bitmap, "a"))
    }

    @Test fun `legacy migration is exact and clear does not erase another account`() = runBlocking {
        val legacy = ProfileIdentityImageStore.save(context, source, bitmap)!!
        val first = ProfileIdentityImageStore.find(context, source, "a")!!
        val second = ProfileIdentityImageStore.find(context, source, "b")!!
        assertNotEquals(legacy, first)
        assertNotEquals(first, second)
        assertNull(ProfileIdentityImageStore.find(context, source.replace("image.png", "different.png"), "a"))
        val usage = ProfileIdentityImageStore.usage(context, "a")!!
        assertFalse(ProfileIdentityImageStore.clear(context, "b", usage.accountKey))
        assertTrue(ProfileIdentityImageStore.clear(context, "a", usage.accountKey))
        assertNull(ProfileIdentityImageStore.find(context, source, "a"))
        assertEquals(second, ProfileIdentityImageStore.find(context, source, "b"))
        assertTrue(legacy.exists())
    }

    @Test fun `blank account during switching does not store or read another account image`() = runBlocking {
        ProfileIdentityImageStore.save(context, source, bitmap, "a")
        assertNull(ProfileIdentityImageStore.save(context, source, bitmap, ""))
        assertNull(ProfileIdentityImageStore.find(context, source, ""))
    }
}
