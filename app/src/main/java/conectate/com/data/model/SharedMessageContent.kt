package conectate.com.data.model

import kotlinx.serialization.Serializable

internal const val SHARED_MESSAGE_TYPE = "shared_content"

@Serializable
internal data class SharedMessageReference(val kind: String, val id: String) {
    init { require(kind == "post" && ID.matches(id)) { "Referencia de publicación no válida." } }

    companion object {
        private val ID = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,99}")
        fun post(id: String) = SharedMessageReference("post", id)
    }
}

enum class SharedMessageDestination(val segment: String) {
    Chat("chats"), Group("groups"), Community("communities")
}

@Serializable
internal data class SharedMessagePreview(val reference: SharedMessageReference, val title: String, val summary: String)

internal class SharedMessageUnavailable : IllegalStateException("La publicación no está disponible o ya no tienes acceso.")