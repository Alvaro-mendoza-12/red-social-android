package conectate.com.data.repository

import android.util.Log
import conectate.com.data.model.TypingConversationScope
import conectate.com.data.model.TypingParticipant
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TypingRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService
) {
    @Serializable
    private data class TypingBroadcastPayload(
        val conversationId: String,
        val targetType: String,
        val userId: String,
        val displayName: String = "",
        val isTyping: Boolean,
        val timestamp: Long
    )

    companion object {
        private const val TAG = "TypingRepository"
        private const val TYPING_EVENT = "typing"
        private const val TYPING_TTL_MS = 9000L
        private const val PRUNE_INTERVAL_MS = 1200L
        private const val FLUSH_DELAY_MS = 180L
    }

    private data class SharedTypingChannel(
        val channel: RealtimeChannel,
        var holderCount: Int
    )

    private val sharedChannels = ConcurrentHashMap<String, SharedTypingChannel>()
    private val channelMutexes = ConcurrentHashMap<String, Mutex>()

    fun observeTyping(
        scopeType: TypingConversationScope,
        conversationId: String
    ): Flow<List<TypingParticipant>> = flow {
        var cleanConversationId = conversationId.trim().lowercase()
        if (cleanConversationId.isBlank()) {
            emit(emptyList())
            return@flow
        }

        var channelName = "${scopeType.channelPrefix}:$cleanConversationId"
        val typingState = MutableStateFlow<List<TypingParticipant>>(emptyList())
        var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
        var channel = acquireSharedChannel(channelName)

        coroutineScope {
            val broadcastJob = launch(Dispatchers.IO) {
                channel.broadcastFlow<TypingBroadcastPayload>(TYPING_EVENT).collect { payload ->
                    var senderId = payload.userId.trim().lowercase()
                    if (senderId.isBlank() || senderId == currentUserId) return@collect

                    var isTyping = payload.isTyping
                    var displayName = payload.displayName
                        .ifBlank { "Alguien" }
                    var updatedAtMs = payload.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis()

                    typingState.update { current ->
                        val withoutSender = current.filterNot {
                            it.userId.equals(senderId, ignoreCase = true)
                        }
                        if (!isTyping) {
                            withoutSender.pruneExpired(updatedAtMs)
                        } else {
                            (withoutSender + TypingParticipant(
                                userId = senderId,
                                displayName = displayName,
                                updatedAtMs = updatedAtMs
                            )).pruneExpired(updatedAtMs)
                        }
                    }
                }
            }

            val pruneJob = launch(Dispatchers.IO) {
                while (isActive) {
                    delay(PRUNE_INTERVAL_MS)
                    typingState.update { it.pruneExpired() }
                }
            }

            val statusJob = launch(Dispatchers.IO) {
                channel.status.collect { status ->
                    if (
                        status == RealtimeChannel.Status.UNSUBSCRIBED ||
                        status.name == "DISCONNECTED" ||
                        status.name == "TIMED_OUT" ||
                        status.name == "CLOSED"
                    ) {
                        typingState.value = emptyList()
                    }
                }
            }

            try {
                emitAll(
                    typingState
                        .map { it.pruneExpired() }
                        .distinctUntilChanged()
                )
            } finally {
                broadcastJob.cancel()
                pruneJob.cancel()
                statusJob.cancel()
                typingState.value = emptyList()
                releaseSharedChannel(channelName)
            }
        }
    }

    suspend fun sendTyping(
        scopeType: TypingConversationScope,
        conversationId: String,
        isTyping: Boolean,
        displayName: String?
    ) {
        var cleanConversationId = conversationId.trim().lowercase()
        var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return
        if (cleanConversationId.isBlank()) return

        var channelName = "${scopeType.channelPrefix}:$cleanConversationId"
        var channel = acquireSharedChannel(channelName)

        try {
            channel.broadcast(
                event = TYPING_EVENT,
                message = TypingBroadcastPayload(
                    conversationId = cleanConversationId,
                    targetType = scopeType.targetType,
                    userId = currentUserId,
                    displayName = displayName?.trim().orEmpty(),
                    isTyping = isTyping,
                    timestamp = System.currentTimeMillis()
                )
            )

            delay(FLUSH_DELAY_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send typing event for $channelName", e)
        } finally {
            releaseSharedChannel(channelName)
        }
    }

    private fun channelMutex(channelName: String): Mutex {
        return channelMutexes.getOrPut(channelName) { Mutex() }
    }

    private suspend fun acquireSharedChannel(channelName: String): RealtimeChannel {
        return channelMutex(channelName).withLock {
            var existing = sharedChannels[channelName]
            if (existing != null && existing.channel.status.value != RealtimeChannel.Status.UNSUBSCRIBED) {
                existing.holderCount += 1
                return@withLock existing.channel
            }

            if (existing != null) {
                runCatching { existing.channel.unsubscribe() }
                sharedChannels.remove(channelName, existing)
            }

            val created = supabaseConfig.client.channel(channelName) {
                broadcast {
                    acknowledgeBroadcasts = false
                    receiveOwnBroadcasts = false
                }
            }

            try {
                supabaseConfig.subscribeRealtimeChannel(
                    channel = created,
                    reason = "typing-$channelName",
                    blockUntilSubscribed = true
                )
                sharedChannels[channelName] = SharedTypingChannel(
                    channel = created,
                    holderCount = 1
                )
                created
            } catch (e: Exception) {
                runCatching { created.unsubscribe() }
                throw e
            }
        }
    }

    private suspend fun releaseSharedChannel(channelName: String) {
        channelMutex(channelName).withLock {
            var existing = sharedChannels[channelName] ?: return
            existing.holderCount -= 1
            if (existing.holderCount > 0) return

            sharedChannels.remove(channelName, existing)
            runCatching { existing.channel.unsubscribe() }
        }
    }

    private fun List<TypingParticipant>.pruneExpired(nowMs: Long = System.currentTimeMillis()): List<TypingParticipant> {
        return filter { nowMs - it.updatedAtMs <= TYPING_TTL_MS }
            .sortedBy { it.displayName.lowercase() }
    }
}



