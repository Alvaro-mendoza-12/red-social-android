package conectate.com.presentation.messages

import android.Manifest
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.service.MessageNotificationReceiverEntryPoint
import conectate.com.core.network.PublicBackendDns
import conectate.com.data.signaling.SignalingConfig
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Camera -> actual editor -> production repositories/upload -> deployed API/WebSocket -> peer download.
 * Opt-in on the fresh owned QA emulator. Neither a human session nor human contacts are used.
 * This verifies the shared composer and each production delivery repository, not every navigation route.
 */
@RunWith(AndroidJUnit4::class)
class CameraMediaDeliveryServerTest {
    @get:Rule val compose = createComposeRule()
    private val base get() = SignalingConfig.BASE_URL.trimEnd('/')
    private val client = OkHttpClient.Builder().dns(PublicBackendDns).callTimeout(45, TimeUnit.SECONDS).build()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun api(path: String, token: String, method: String = "GET", body: JSONObject? = null, expected: Int = 200): JSONObject {
        val payload = if (method == "GET" || method == "DELETE") null
            else (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(base + path).header("Authorization", "Bearer $token").method(method, payload).build())
            .execute().use { response ->
                val text = response.body?.string().orEmpty()
                assertEquals("HTTP contract $method $path", expected, response.code)
                return if (text.isBlank()) JSONObject() else JSONObject(text)
            }
    }

    private fun readyAction(description: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Cámara lista"))
                    .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(description).assertIsEnabled()
    }

    @Test(timeout = 420_000) fun photoAndTrimmedVideoReachOwnPeerInDirectGroupAndCommunity() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("auditCameraDelivery") == "true")
        val accounts = JSONArray(File(context.cacheDir, "media-qa-accounts.json").readText())
        assertEquals(2, accounts.length())
        val owner = accounts.getJSONObject(0)
        val peer = accounts.getJSONObject(1)
        val entry = EntryPointAccessors.fromApplication(context, MessageNotificationReceiverEntryPoint::class.java)
        compose.waitUntil(15_000) { !entry.authService().isInitializing.value }
        assertEquals("Never substitute an unrelated user's session", owner.getString("id"), entry.authService().getCurrentUserId())
        val ownerToken = requireNotNull(entry.authService().getAccessToken())
        val peerToken = peer.getString("token")
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        val nonce = UUID.randomUUID().toString()
        val rooms = mutableListOf<Pair<String, String>>()
        val sentRows = mutableListOf<Triple<String, String, JSONObject>>()
        val localFiles = mutableListOf<Uri>()
        val captureDirectory = File(context.cacheDir, "file-provider/chat-captures")
        val capturesBefore = captureDirectory.list()?.toSet().orEmpty()
        val events = CopyOnWriteArrayList<JSONObject>()
        val subscribed = AtomicBoolean(false)
        val socketError = AtomicReference<Throwable?>()
        val socket = client.newWebSocket(Request.Builder().url(base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/realtime/")
            .header("Authorization", "Bearer $peerToken").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val value = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (value.optString("type")) {
                    "connected" -> webSocket.send(JSONObject().put("type", "subscribe").put("channel", "user:${peer.getString("id")}").toString())
                    "subscribed" -> if (value.optString("channel") == "user:${peer.getString("id")}") subscribed.set(true)
                    "event" -> value.optJSONObject("payload")?.let(events::add)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { socketError.set(t) }
        })
        var shown by mutableStateOf(false)
        var editorKey by mutableStateOf("")
        var currentCollection = "chats"
        var currentRoom = ""
        val outgoing = AtomicReference<Pair<OutgoingMessageMedia, String>?>()
        compose.setContent {
            MaterialTheme {
                if (shown) key(editorKey) {
                    MessageMediaComposer(initialStep = "camera", maxItems = 1, isSending = false,
                        onDismiss = { shown = false }, onSend = { items, caption ->
                            assertEquals(1, items.size)
                            outgoing.set(items.single() to caption)
                            shown = false
                        })
                }
            }
        }
        val reports = JSONArray()
        var primaryFailure: Throwable? = null
        try {
            compose.waitUntil(20_000) { subscribed.get() || socketError.get() != null }
            assertNull("Own receiver WebSocket connection failed", socketError.get())
            assertTrue(subscribed.get())
            for (collection in listOf("chats", "groups", "communities")) {
                val created = if (collection == "chats") {
                    api("/v1/data/messages/chats", ownerToken, "POST", JSONObject().put("participantId", peer.getString("id")), 201)
                } else {
                    api("/v1/data/messages/$collection", ownerToken, "POST", JSONObject().put("name", "QA cámara $nonce")
                        .put("isPublic", false).put("memberIds", JSONArray().put(peer.getString("id"))), 201)
                }
                val room = created.optString("id").takeIf(String::isNotBlank)
                    ?: (created.optJSONObject("chat") ?: created.getJSONObject("room")).getString("id")
                rooms += collection to room
                // The fixture creates its room through HTTP instead of the UI.
                // Populate the parent Room row through the normal repository sync
                // before exercising its message foreign keys and delivery path.
                runBlocking {
                    when (collection) {
                        "groups" -> {
                            entry.groupRepository().syncGroups().getOrThrow()
                            assertNotNull(entry.groupRepository().getGroupById(room))
                        }
                        "communities" -> {
                            entry.communityRepository().syncCommunities().getOrThrow()
                            assertNotNull(entry.communityRepository().getCommunityById(room))
                        }
                    }
                }
                val path = "/v1/data/messages/$collection/$room"
                for (kind in listOf("image", "video")) {
                    val cameraFilesBefore = captureDirectory.list()?.toSet().orEmpty()
                    var capturedDuration = 0L
                    var confirmedTrimDuration = 0L
                    currentCollection = collection
                    currentRoom = room
                    outgoing.set(null)
                    val caption = "QA captura $nonce $collection $kind"
                    compose.runOnIdle { editorKey = "$collection-$kind"; shown = true }
                    readyAction("Tomar foto")
                    if (kind == "video") {
                        compose.onNodeWithText("Video").performClick()
                        readyAction("Grabar video")
                        compose.onNodeWithContentDescription("Grabar video").performClick()
                        compose.waitUntil(15_000) {
                            val timer = compose.onAllNodesWithContentDescription("Duración de grabación").fetchSemanticsNodes().singleOrNull()
                            (timer?.config?.get(SemanticsProperties.StateDescription)?.substringBefore(' ')?.toIntOrNull() ?: 0) >= 4
                        }
                        compose.onNodeWithContentDescription("Detener grabación").performClick()
                    } else compose.onNodeWithContentDescription("Tomar foto").performClick()
                    compose.waitUntil(25_000) { compose.onAllNodesWithContentDescription("Enviar").fetchSemanticsNodes().isNotEmpty() }
                    assertNull("Capture sent automatically before confirmation", outgoing.get())
                    compose.onNode(hasSetTextAction()).performTextInput(caption)
                    if (kind == "image") {
                        compose.onNodeWithContentDescription("Girar").performClick()
                        compose.waitUntil(15_000) {
                            compose.onAllNodesWithContentDescription("Enviar").fetchSemanticsNodes().singleOrNull()
                                ?.config?.contains(SemanticsProperties.Disabled) == false
                        }
                        compose.onNodeWithTag("message_photo_preview").assertIsDisplayed()
                    } else {
                        val captured = captureDirectory.listFiles().orEmpty().single {
                            it.extension == "mp4" && it.name !in cameraFilesBefore
                        }
                        capturedDuration = MediaMetadataRetriever().use { retriever ->
                            retriever.setDataSource(captured.path)
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                        }
                        compose.onNodeWithTag("message_video_trim").performTouchInput {
                            swipe(Offset(width - 16f, centerY), Offset(width * .68f, centerY), 400)
                        }
                        fun trimBounds() = compose.onAllNodes(
                            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
                        ).fetchSemanticsNodes().map { it.config[SemanticsProperties.ProgressBarRangeInfo] }
                            .filter { it.range.endInclusive >= 1_000f }
                        compose.waitUntil(5_000) {
                            trimBounds().maxOfOrNull { it.current }?.let { it in 800f..(capturedDuration * .9f) } == true
                        }
                        val bounds = trimBounds()
                        confirmedTrimDuration = (bounds.maxOf { it.current } - bounds.minOf { it.current }).toLong()
                    }
                    compose.onNodeWithContentDescription("Enviar").performClick()
                    compose.waitUntil(45_000) { outgoing.get() != null }
                    val (item, confirmedCaption) = requireNotNull(outgoing.get())
                    assertEquals(kind, item.type)
                    assertEquals(caption, confirmedCaption)
                    localFiles += item.uri
                    val localDuration = if (kind == "video") {
                        MediaMetadataRetriever().use { retriever ->
                            retriever.setDataSource(context, item.uri)
                            assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                                .also {
                                    assertTrue("Trim did not shorten the actual recorded clip", it in 800..(capturedDuration - 250))
                                    assertTrue("Export does not match the selected trim bounds",
                                        kotlin.math.abs(it - confirmedTrimDuration) <= 200L)
                                }
                        }
                    } else 0L
                    runBlocking(Dispatchers.IO) {
                        when (currentCollection) {
                            "chats" -> entry.messageRepository().sendMessage(currentRoom, confirmedCaption, mediaUri = item.uri, mediaType = item.type).getOrThrow()
                            "groups" -> entry.groupRepository().sendGroupMediaMessage(currentRoom, item.uri, item.type, confirmedCaption).getOrThrow()
                            else -> entry.communityRepository().sendCommunityMediaMessage(currentRoom, item.uri, item.type, confirmedCaption).getOrThrow()
                        }
                    }
                    var row: JSONObject? = null
                    val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
                    while (row == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                        val received = api(path, peerToken)
                        val messages = received.optJSONArray("messages")
                            ?: received.optJSONObject("chat")?.optJSONArray("messages")
                            ?: received.optJSONObject("room")?.optJSONArray("messages") ?: JSONArray()
                        row = (0 until messages.length()).map(messages::getJSONObject)
                            .firstOrNull { it.optString("content") == caption && it.optString("sender_id") == owner.getString("id") }
                        if (row == null) Thread.sleep(250)
                    }
                    val message = requireNotNull(row) { "Own receiver could not read the authoritative uploaded message" }
                    sentRows += Triple(collection, room, message)
                    assertEquals(kind, message.getString("content_type"))
                    val id = message.getString("id")
                    compose.waitUntil(20_000) { events.any { it.optString("event") == "message" && it.optJSONObject("message")?.optString("id") == id } }
                    val realtime = events.first { it.optJSONObject("message")?.optString("id") == id }
                    assertEquals(caption, realtime.getJSONObject("message").getString("content"))
                    assertEquals(owner.getString("id"), realtime.getJSONObject("message").getString("sender_id"))
                    val remoteUrl = message.getString("media_url")
                    val download = File(context.cacheDir, "qa_delivery_${UUID.randomUUID()}.${if (kind == "video") "mp4" else "jpg"}")
                    localFiles += Uri.fromFile(download)
                    client.newCall(Request.Builder().url(remoteUrl).header("Authorization", "Bearer $peerToken").build()).execute().use { response ->
                        assertEquals(200, response.code)
                        response.body!!.byteStream().use { source -> download.outputStream().use { target -> source.copyTo(target) } }
                    }
                    if (kind == "image") {
                        val local = context.contentResolver.openInputStream(item.uri)?.use(BitmapFactory::decodeStream)
                        val remote = BitmapFactory.decodeFile(download.path)
                        assertNotNull(local); assertNotNull(remote)
                        try { assertEquals(local!!.width, remote!!.width); assertEquals(local.height, remote.height) }
                        finally { local?.recycle(); remote?.recycle() }
                    } else MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(download.path)
                        assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                        val downloadedDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                        // AAC encoding adds frame padding; validate the trim with a bounded
                        // container-duration tolerance instead of requiring byte-copy timing.
                        assertTrue("Normalized video no longer matches the confirmed trim",
                            kotlin.math.abs(downloadedDuration - localDuration) <= 200L)
                        requireNotNull(retriever.getFrameAtTime(500_000)).recycle()
                    }
                    reports.put(JSONObject().put("context", collection).put("type", kind).put("cameraAndEditor", true)
                        .put("confirmationRequired", true).put("productionRepositoryAndUpload", true)
                        .put("peerHttpAndRealtime", true).put("downloadDecoded", true).put("durationMs", localDuration)
                        .put("capturedDurationMs", capturedDuration).put("confirmedTrimDurationMs", confirmedTrimDuration))
                }
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            compose.runOnIdle { shown = false }
            socket.close(1000, "Own QA complete")
            val cleanupFailures = mutableListOf<String>()
            for ((collection, room, row) in sentRows.asReversed()) {
                for (field in listOf("media_url", "thumbnail_url")) {
                    val url = row.optString(field)
                    if (url.isBlank() || url == "null") continue
                    runCatching {
                        val storage = Uri.parse(url).path!!.substringAfter("/storage/v1/object/public/", "")
                        val bucket = storage.substringBefore('/')
                        val objectPath = storage.substringAfter('/')
                        require(bucket in setOf("chat-media", "group-media", "community-media", "groups", "communities", "thumbnails"))
                        require(objectPath.contains(room) && !objectPath.contains(".."))
                        api("/v1/storage/objects/$bucket/$objectPath", ownerToken, "DELETE")
                    }.onFailure { cleanupFailures += "storage:$collection:${row.optString("id")}:${it.javaClass.simpleName}:${it.message}" }
                }
            }
            for ((collection, room) in rooms.asReversed()) {
                runCatching { api("/v1/data/messages/$collection/$room", ownerToken, "DELETE") }
                    .onFailure { cleanupFailures += "room:$collection:$room" }
            }
            localFiles.forEach { uri ->
                if (uri.scheme == "file") {
                    val file = File(requireNotNull(uri.path)).canonicalFile
                    if (file.parentFile == context.cacheDir.canonicalFile &&
                        (file.name.startsWith("message_media_") || file.name.startsWith("message_video_") || file.name.startsWith("qa_delivery_"))) file.delete()
                }
            }
            captureDirectory.listFiles().orEmpty().filter { it.name !in capturesBefore }.forEach { it.delete() }
            File(context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }, "camera-delivery-cleanup-private.json")
                .writeText(JSONObject().put("failures", JSONArray(cleanupFailures)).put("completedChecks", reports)
                    .put("primaryFailure", primaryFailure?.javaClass?.simpleName).toString())
            if (cleanupFailures.isNotEmpty()) {
                val failure = AssertionError("Owned media cleanup incomplete; see private cleanup report")
                if (primaryFailure == null) throw failure else primaryFailure!!.addSuppressed(failure)
            }
            assertEquals(owner.getString("id"), entry.authService().getCurrentUserId())
        }
        assertEquals(6, reports.length())
        File(context.getExternalFilesDir("communication-qa")!!, "camera-media-delivery.json").writeText(
            JSONObject().put("ownQaOnly", true).put("humanSessionNeverReplaced", true).put("checks", reports)
                .put("ownedRoomsAndObjectsRemoved", true).toString(2))
    }
}
