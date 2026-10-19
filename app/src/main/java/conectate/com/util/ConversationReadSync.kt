package conectate.com.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class ConversationReadEvent(val accountId: String, val kind: String, val conversationId: String, val messageIds: Set<String>, val through: String?) {
    fun includes(messageId: String?, createdAt: String?): Boolean = messageId?.lowercase() in messageIds ||
        (through != null && createdAt != null && runCatching { !Instant.parse(createdAt).isAfter(Instant.parse(through)) }.getOrDefault(false))
}

fun conversationReadEvent(data: Map<String,String>): ConversationReadEvent? = runCatching {
    if (data["type"] != "conversation_read") return null
    val account = UUID.fromString(data["recipient_id"]).toString()
    val id = UUID.fromString(data["conversation_id"]).toString()
    val kind = data["target_type"].orEmpty()
    if (kind !in setOf("chat","group","community")) return null
    val raw = JSONArray(data["message_ids"] ?: "[]")
    if (raw.length() > 32) return null
    val ids = (0 until raw.length()).map { UUID.fromString(raw.getString(it)).toString() }.toSet()
    val through = data["read_through"]?.takeIf(String::isNotBlank)?.let { Instant.parse(it).toString() }
    if (ids.isEmpty() && through == null) return null
    ConversationReadEvent(account,kind,id,ids,through)
}.getOrNull()

class ConversationReadStore(context: Context) {
    private val prefs = context.getSharedPreferences("conversation_read_sync_v1",Context.MODE_PRIVATE)
    private fun key(account: String, kind: String, id: String) = "$account/$kind/$id".lowercase()
    @Synchronized fun remember(event: ConversationReadEvent) {
        val name=key(event.accountId,event.kind,event.conversationId)
        val previous=runCatching { JSONObject(prefs.getString(name,null).orEmpty()) }.getOrNull()
        val ids=linkedSetOf<String>()
        previous?.optJSONArray("ids")?.let { array -> for(i in 0 until array.length()) ids.add(array.getString(i)) }
        ids.addAll(event.messageIds)
        val oldThrough=previous?.optString("through")?.takeIf(String::isNotBlank)
        val through=listOfNotNull(oldThrough,event.through).maxByOrNull { runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0) }
        val value=JSONObject().put("ids",JSONArray(ids.toList().takeLast(200))).put("through",through.orEmpty()).put("at",System.currentTimeMillis())
        val editor=prefs.edit().putString(name,value.toString())
        prefs.all.entries.filter { it.key!=name }.sortedByDescending { runCatching { JSONObject(it.value.toString()).optLong("at") }.getOrDefault(0) }
            .drop(99).forEach { editor.remove(it.key) }
        editor.apply()
    }
    fun isRead(account: String?, kind: String, id: String?, messageId: String?, createdAt: String?=null): Boolean {
        if(account.isNullOrBlank()||id.isNullOrBlank())return false
        return runCatching {
            val value=JSONObject(prefs.getString(key(account,kind,id),null)?:return false)
            if(System.currentTimeMillis()-value.optLong("at") > 7L*24*60*60*1000)return false
            val array=value.optJSONArray("ids")?:JSONArray()
            ConversationReadEvent(account,kind,id,(0 until array.length()).map { array.getString(it) }.toSet(),value.optString("through").takeIf(String::isNotBlank)).includes(messageId,createdAt)
        }.getOrDefault(false)
    }
}
