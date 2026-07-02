package conectate.com.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import conectate.com.data.local.dao.ChatSettingsDao
import conectate.com.data.local.entity.ChatSettingsEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class ChatBackgroundSettings(
    val backgroundType: String = "default",
    val backgroundUri: String? = null
)

@Singleton
class ChatSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatSettingsDao: ChatSettingsDao
) {
    fun observeChatBackground(chatId: String): Flow<ChatBackgroundSettings> {
        var cleanId = chatId.trim().lowercase()
        return chatSettingsDao.observeByChatId(cleanId).map { entity ->
            if (entity == null) {
                ChatBackgroundSettings()
            } else {
                ChatBackgroundSettings(
                    backgroundType = entity.backgroundType.ifBlank { "default" },
                    backgroundUri = entity.backgroundUri
                )
            }
        }
    }

    suspend fun upsertChatBackground(chatId: String, backgroundType: String, backgroundUri: String?) {
        var cleanId = chatId.trim().lowercase()
        if (cleanId.isBlank()) return
        val normalizedType = backgroundType.trim().lowercase().ifBlank { "default" }
        chatSettingsDao.upsert(
            ChatSettingsEntity(
                chatId = cleanId,
                backgroundType = normalizedType,
                backgroundUri = backgroundUri?.trim()?.ifBlank { null },
                updatedAt = java.time.Instant.now().toString()
            )
        )
    }

    suspend fun clearChatBackground(chatId: String) {
        var cleanId = chatId.trim().lowercase()
        if (cleanId.isBlank()) return
        chatSettingsDao.clearByChatId(cleanId)
    }

    suspend fun setChatBackground(chatId: String, backgroundType: String, sourceUri: String? = null) {
        val cleanId = chatId.trim().lowercase()
        if (cleanId.isBlank()) return
        val normalizedType = backgroundType.trim().lowercase().ifBlank { "default" }
        val resolvedUri = if (normalizedType == "custom" && !sourceUri.isNullOrBlank()) {
            optimizeAndCacheBackground(cleanId, sourceUri) ?: sourceUri
        } else {
            sourceUri
        }
        upsertChatBackground(cleanId, normalizedType, resolvedUri)
    }

    private suspend fun optimizeAndCacheBackground(chatId: String, sourceUri: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = Uri.parse(sourceUri)
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            var sampleSize = 1
            while (bounds.outWidth / sampleSize > 1440 || bounds.outHeight / sampleSize > 1440) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return@runCatching null
            val directory = File(context.filesDir, "chat_backgrounds").apply { mkdirs() }
            val safeName = chatId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val target = File(directory, "bg_$safeName.jpg")
            FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            bitmap.recycle()
            Uri.fromFile(target).toString()
        }.getOrNull()
    }
}




