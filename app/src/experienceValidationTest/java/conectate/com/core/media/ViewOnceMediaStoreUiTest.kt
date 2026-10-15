package conectate.com.core.media

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Timeout
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.io.IOException
import java.net.URI

/** Actual Android private cache/lifecycle; synthetic callback bytes, no network. */
class ViewOnceMediaStoreUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val identity = StorageReadIdentity("view-once-owned-fixture", 1)
    private val credential = StorageReadCredential(identity, "synthetic.jwt")
    private var current: StorageReadCredential? = credential
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory get() = File(context.cacheDir, "view-once-private")
    private val managed = mutableListOf<String>()
    @Before fun prepare() {
        compose.setContent { Text("Vista única: prueba local") }
        compose.waitForIdle()
        compose.waitUntil(5_000) { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        StorageReadSession.install { current }
    }
    @After fun cleanup() {
        managed.forEach(ViewOnceMediaStore::release)
        StorageReadSession.install { null }
    }
    private fun lease(bytes: Long = 3, expiry: Long = System.currentTimeMillis()+60_000) = ViewOnceLease(
        conectate.com.data.localfallback.UbuntuStorageUrl.baseUrl + "/storage/v1/object/public/chat-media/view-once-owned-fixture/local.png?lease=" + "a".repeat(43),
        bytes, "view_once_image", expiry
    )
    private fun download(calls: Factory, value: ViewOnceLease = lease()): String = runBlocking {
        ViewOnceMediaStore.download(context, value, calls).also { managed.add(it) }
    }
    private fun privateFiles() = directory.listFiles()?.filter { it.name.startsWith("once-") }.orEmpty()

    @Test fun oneReadProducesExactPrivateFileAndReleaseDeletesIt() {
        val calls=Factory();val url=download(calls);val file=File(URI(url))
        assertTrue(ViewOnceMediaStore.owns(url));assertEquals(directory.canonicalFile,file.parentFile.canonicalFile)
        assertArrayEquals(byteArrayOf(1,2,3),file.readBytes());assertEquals(1,calls.count)
        assertNull(calls.call.request().header("Range"));assertEquals("no-store",calls.call.request().header("Cache-Control"))
        assertTrue(calls.body.closed);ViewOnceMediaStore.release(url)
        assertFalse(file.exists());assertFalse(ViewOnceMediaStore.owns(url))
    }
    @Test fun accountGenerationChangeRevokesOwnership() {
        val url=download(Factory());current=StorageReadCredential(identity.copy(generation=2),"synthetic.jwt")
        assertFalse(ViewOnceMediaStore.owns(url));ViewOnceMediaStore.release(url);assertFalse(File(URI(url)).exists())
    }
    @Test fun expiredLeaseDoesNotSubmitARequestOrLeaveAFile() {
        val calls=Factory();val before=privateFiles().toSet()
        assertThrows(IllegalArgumentException::class.java) { download(calls,lease(expiry=System.currentTimeMillis()-1)) }
        assertEquals(0,calls.count);assertEquals(before,privateFiles().toSet())
    }
    @Test fun missingMimeDeletesTemporaryFile() {
        val calls=Factory(Body(mime=null));val before=privateFiles().toSet()
        assertThrows(IOException::class.java) { download(calls) };assertEquals(1,calls.count)
        assertTrue(calls.body.closed);assertEquals(before,privateFiles().toSet())
    }
    @Test fun unexpectedMimeDeletesTemporaryFile() {
        val calls=Factory(Body(mime="application/octet-stream"));val before=privateFiles().toSet()
        assertThrows(IOException::class.java) { download(calls) };assertTrue(calls.body.closed);assertEquals(before,privateFiles().toSet())
    }
    @Test fun truncatedResponseCannotPublishAFile() {
        val calls=Factory(Body(bytes=byteArrayOf(1,2),declared=3));val before=privateFiles().toSet()
        assertThrows(IOException::class.java) { download(calls) };assertTrue(calls.body.closed);assertEquals(before,privateFiles().toSet())
    }
    @Test fun processBackgroundRemovesCompletedPrivateFile() {
        val url=download(Factory());val file=File(URI(url));assertTrue(file.exists())
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val deadline=System.currentTimeMillis()+5_000
        while(file.exists()&&System.currentTimeMillis()<deadline)Thread.sleep(50)
        assertFalse(file.exists());assertFalse(ViewOnceMediaStore.owns(url))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }
    @Test fun processBackgroundCancelsPendingReadAndLateResponseIsClosed(): Unit = runBlocking {
        val calls=Factory(immediate=false);val before=privateFiles().toSet()
        val task=async { ViewOnceMediaStore.download(context,lease(),calls) };yield()
        assertEquals(1,calls.count)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val deadline=System.currentTimeMillis()+5_000
        while(!calls.call.isCanceled()&&System.currentTimeMillis()<deadline)Thread.sleep(50)
        assertTrue(calls.call.isCanceled());calls.respond();task.join()
        assertTrue(task.isCancelled);assertTrue(calls.body.closed);assertEquals(before,privateFiles().toSet())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }
    private class Body(bytes: ByteArray=byteArrayOf(1,2,3),private val declared: Long=bytes.size.toLong(),private val mime: String?="image/png"):ResponseBody(){
        var closed=false;private val data=Buffer().write(bytes)
        override fun contentType():MediaType?=mime?.toMediaType()
        override fun contentLength()=declared
        override fun source()=data
        override fun close(){closed=true;super.close()}
    }
    private class Factory(val body:Body=Body(),private val immediate:Boolean=true):Call.Factory{
        var count=0;lateinit var call:FakeCall;private lateinit var callback:Callback
        override fun newCall(request:Request):Call{count++;call=FakeCall(request){callback=it;if(immediate)respond()};return call}
        fun respond(){callback.onResponse(call,Response.Builder().request(call.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(body).build())}
    }
    private class FakeCall(private val req:Request,private val respond:(Callback)->Unit):Call{
        private var executed=false;@Volatile private var cancelled=false;private val deadline=Timeout()
        override fun request()=req
        override fun execute():Response=throw UnsupportedOperationException("Zero network fixture")
        override fun enqueue(callback:Callback){executed=true;respond(callback)}
        override fun cancel(){cancelled=true}
        override fun isExecuted()=executed
        override fun isCanceled()=cancelled
        override fun timeout()=deadline
        override fun clone():Call=FakeCall(req,respond)
    }
}
