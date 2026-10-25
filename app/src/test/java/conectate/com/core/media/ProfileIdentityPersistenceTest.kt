package conectate.com.core.media

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
        assertEquals(saved, ProfileIdentityImageStore.peek(rotated, "a"))
        assertNull(ProfileIdentityImageStore.peek(rotated, "b"))
        assertNull(ProfileIdentityImageStore.find(context, rotated, "b"))
    }

    @Test fun `late save captured before clear cannot resurrect an image`() = runBlocking {
        val generation = ProfileIdentityImageStore.generation("a")
        ProfileIdentityImageStore.save(context, source, bitmap, "a", generation)
        val usage = ProfileIdentityImageStore.usage(context, "a")!!
        assertTrue(ProfileIdentityImageStore.clear(context, "a", usage.accountKey))
        assertNull(ProfileIdentityImageStore.peek(source, "a"))
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

    @Test fun `decoded preview is ready without IO after save and cannot cross accounts or survive clear`() = runBlocking {
        val account = "preview-${java.util.UUID.randomUUID()}"
        val sourceBitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        try {
            ProfileIdentityImageStore.save(context, source, sourceBitmap, account)
            val preview = checkNotNull(ProfileIdentityImageStore.peekPreview(source, account))
            sourceBitmap.recycle()
            assertFalse(preview.isRecycled)
            val pixel = preview.getPixel(24, 24)
            assertTrue(android.graphics.Color.red(pixel) > 245 && android.graphics.Color.blue(pixel) > 245)
            assertTrue(android.graphics.Color.green(pixel) < 10)
            assertNull(ProfileIdentityImageStore.peekPreview(source, account + "-other"))
            val usage = checkNotNull(ProfileIdentityImageStore.usage(context, account))
            assertTrue(ProfileIdentityImageStore.clear(context, account, usage.accountKey))
            assertNull(ProfileIdentityImageStore.peekPreview(source, account))
            // Clearing a cache must not recycle pixels that an existing UI frame owns.
            assertFalse(preview.isRecycled)
        } finally { if (!sourceBitmap.isRecycled) sourceBitmap.recycle() }
    }

    @Test fun `loaded avatar remains ready and persists after its displaying screen is cancelled`() = runBlocking {
        val account = "navigation-${java.util.UUID.randomUUID()}"
        val loaded = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        try {
            val screen = launch(start = CoroutineStart.UNDISPATCHED) {
                val retained = ProfileIdentityImageStore.retainDrawable(
                    context, source, BitmapDrawable(context.resources, loaded), account,
                )
                // A decoded static image does not need rasterization in its UI callback.
                assertSame(loaded, retained)
                assertNotNull(ProfileIdentityImageStore.peekPreview(source, account))
                awaitCancellation()
            }
            screen.cancelAndJoin()
            awaitRetention(account)
            assertFalse("The store must not recycle Coil's bitmap", loaded.isRecycled)
            assertNull(ProfileIdentityImageStore.peekPreview(source, "$account-other"))
            val usage = checkNotNull(ProfileIdentityImageStore.usage(context, account))
            assertTrue(usage.imageCount > 0)
            assertTrue(ProfileIdentityImageStore.clear(context, account, usage.accountKey))
            assertNull(ProfileIdentityImageStore.peekPreview(source, account))
        } finally { loaded.recycle() }
    }

    @Test fun `animated drawable snapshot is bounded and a late callback cannot undo clearing its account`() = runBlocking {
        val account = "drawable-${java.util.UUID.randomUUID()}"
        val generation = ProfileIdentityImageStore.generation(account)
        val drawable = object : Drawable() {
            override fun getIntrinsicWidth() = 4096
            override fun getIntrinsicHeight() = 8192
            override fun draw(canvas: Canvas) { canvas.drawColor(android.graphics.Color.CYAN) }
            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(colorFilter: ColorFilter?) = Unit
            @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.OPAQUE
        }
        val preview = checkNotNull(ProfileIdentityImageStore.retainDrawable(context, source, drawable, account, generation))
        assertEquals(128, preview.width)
        assertEquals(256, preview.height)
        assertEquals(android.graphics.Color.CYAN, preview.getPixel(64, 128))
        awaitRetention(account)
        val usage = checkNotNull(ProfileIdentityImageStore.usage(context, account))
        assertTrue(ProfileIdentityImageStore.clear(context, account, usage.accountKey))
        assertNull(ProfileIdentityImageStore.retainDrawable(context, source, drawable, account, generation))
        assertNull(ProfileIdentityImageStore.peekPreview(source, account))
        assertFalse("Clearing must not recycle pixels still owned by a displayed frame", preview.isRecycled)
    }

    private suspend fun awaitRetention(account: String) = withTimeout(15_000) {
        while (ProfileIdentityImageStore.peek(source, account)?.isFile != true) delay(10)
        // usage serializes with the store's writer, ensuring all encoding work
        // finished before the test clears files or releases its input bitmap.
        checkNotNull(ProfileIdentityImageStore.usage(context, account))
    }
}
