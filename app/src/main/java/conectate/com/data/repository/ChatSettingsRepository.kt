package conectate.com.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import conectate.com.data.local.dao.ChatSettingsDao
import conectate.com.data.local.entity.ChatSettingsEntity
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val chatSettingsDao: ChatSettingsDao,
    private val authService: AuthService
) {
    private val store = AccountChatBackgroundStore(
        currentOwner = authService::getCurrentUserId,
        owners = authService.observeSession().map { authService.getCurrentUserId() },
        read = { key -> chatSettingsDao.observeByChatId(key).map { entity ->
            entity?.let { ChatBackgroundSettings(it.backgroundType.ifBlank { "default" }, it.backgroundUri) }
                ?: ChatBackgroundSettings()
        } },
        write = { key, settings -> chatSettingsDao.upsert(ChatSettingsEntity(
            chatId = key, backgroundType = settings.backgroundType, backgroundUri = settings.backgroundUri,
            updatedAt = java.time.Instant.now().toString()
        )) },
        clear = chatSettingsDao::clearByChatId,
        prepare = ::optimizeAndCacheBackground
    )

    fun observeChatBackground(chatId: String): Flow<ChatBackgroundSettings> = store.observe(chatId)

    suspend fun setChatBackground(chatId: String, backgroundType: String, sourceUri: String? = null, expectedOwner: String) {
        store.set(expectedOwner, chatId, backgroundType, sourceUri)
    }

    suspend fun clearChatBackground(chatId: String, expectedOwner: String) {
        store.remove(expectedOwner, chatId)
    }

    private suspend fun optimizeAndCacheBackground(chatId: String, sourceUri: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = Uri.parse(sourceUri)
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            var sampleSize = 1
            while (bounds.outWidth / sampleSize > 2160 || bounds.outHeight / sampleSize > 3840) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return@runCatching null
            val scale = minOf(1080f / bitmap.width, 1920f / bitmap.height, 1f)
            val outputBitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                ).also { bitmap.recycle() }
            } else {
                bitmap
            }
            val directory = File(context.filesDir, "chat_backgrounds").apply { mkdirs() }
            val target = File(directory, chatBackgroundFileName(chatId))
            var quality = 84
            var encoded: ByteArray
            do {
                encoded = ByteArrayOutputStream().use { output ->
                    outputBitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                    output.toByteArray()
                }
                quality -= 8
            } while (encoded.size > 1_500_000 && quality >= 52)
            FileOutputStream(target).use { it.write(encoded) }
            outputBitmap.recycle()
            Uri.fromFile(target).toString()
        }.getOrNull()
    }
}




