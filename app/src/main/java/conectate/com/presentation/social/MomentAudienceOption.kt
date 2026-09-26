package conectate.com.presentation.social

enum class MomentAudienceOption(
    val storageValue: String,
    val label: String,
    val description: String,
    val requiresPeople: Boolean = false
) {
    EVERYONE("all", "Todos", "Cualquier persona puede verlo"),
    FOLLOWERS("followers", "Quienes te siguen", "Solo tus seguidores"),
    FOLLOWING("following", "Personas que sigues", "Solo las cuentas que tú sigues"),
    CHATS("chats", "Personas con las que hablas", "Solo contactos con un chat directo"),
    ONLY_SELECTED("only_selected", "Solo compartir con…", "Únicamente las personas elegidas", true),
    EXCEPT_SELECTED("except_selected", "Mis contactos, excepto…", "Todos menos las personas elegidas", true),
    ONLY_ME("only_me", "Solo yo", "Nadie más podrá verlo");
}
