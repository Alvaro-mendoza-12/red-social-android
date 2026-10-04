package conectate.com.data.model

/** Stable wire values; old moments and unknown values retain the card default. */
enum class MomentMusicPresentation(val wireValue: String) {
    CARD("card"), LABEL("label"), AUDIO_ONLY("audio"), LYRICS("lyrics");

    companion object {
        fun fromWire(value: String?): MomentMusicPresentation =
            entries.firstOrNull { it.wireValue == value } ?: CARD
    }
}
