package conectate.com.presentation.videos

import android.net.Uri
import android.os.Bundle
import android.os.Parcel

/** A small project checkpoint. Media bytes and bitmaps never enter saved instance state. */
internal data class EditorDraftState(
    val timeline: EditorTimeline,
    val selectedClipId: Long?,
    val positionMs: Long,
    val quality: ExportQuality,
) {
    fun toBundle(): Bundle? = runCatching {
        require(timeline.tracks.size <= 8 && timeline.tracks.sumOf { it.clips.size } <= 128)
        val bundle = Bundle().apply {
            putInt("schema", 1)
            selectedClipId?.let { putLong("selected", it) }
            putLong("position", positionMs.coerceAtLeast(0))
            putString("quality", quality.name)
            putParcelableArrayList("tracks", ArrayList(timeline.tracks.map { track -> Bundle().apply {
                putLong("id", track.id); putString("type", track.type.name)
                putParcelableArrayList("clips", ArrayList(track.clips.map { clip -> Bundle().apply {
                    putLong("id", clip.id); putString("type", clip.type.name)
                    putString("uri", clip.uri?.toString()); putString("text", clip.text)
                    clip.resId?.let { putInt("resId", it) }
                    putLong("startMs", clip.startMs); putLong("durationMs", clip.durationMs)
                    putLong("sourceDurationMs", clip.sourceDurationMs); putLong("startOffsetMs", clip.startOffsetMs)
                    putFloat("volume", clip.volume); putFloat("speed", clip.speed)
                    putFloat("x", clip.x); putFloat("y", clip.y); putInt("color", clip.color)
                    putFloat("fontSize", clip.fontSize); putFloat("brightness", clip.brightness)
                    putFloat("contrast", clip.contrast); putFloat("saturation", clip.saturation)
                    putString("filterType", clip.filterType.name); putString("aspectRatio", clip.aspectRatio.name)
                    putString("customLutId", clip.customLutId); putString("customLutTitle", clip.customLutTitle)
                    putFloat("originalAudioVolume", clip.originalAudioVolume); putFloat("musicVolume", clip.musicVolume)
                } }))
            } }))
        }
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            require(parcel.dataSize() <= 160 * 1024)
        } finally { parcel.recycle() }
        bundle
    }.getOrNull()

    companion object {
        @Suppress("DEPRECATION")
        fun fromBundle(bundle: Bundle?): EditorDraftState? = runCatching {
            require(bundle != null && bundle.getInt("schema") == 1)
            val tracks = bundle.getParcelableArrayList<Bundle>("tracks") ?: error("Missing tracks")
            require(tracks.size <= 8)
            val timeline = EditorTimeline(tracks.map { track ->
                val clips = track.getParcelableArrayList<Bundle>("clips") ?: error("Missing clips")
                EditorTrack(track.getLong("id"), TrackType.valueOf(track.getString("type")!!), clips.map { clip ->
                    EditorClip(
                        id = clip.getLong("id"), type = ClipType.valueOf(clip.getString("type")!!),
                        uri = clip.getString("uri")?.let(Uri::parse), text = clip.getString("text"),
                        resId = if (clip.containsKey("resId")) clip.getInt("resId") else null,
                        startMs = clip.getLong("startMs"), durationMs = clip.getLong("durationMs"),
                        sourceDurationMs = clip.getLong("sourceDurationMs"), startOffsetMs = clip.getLong("startOffsetMs"),
                        volume = clip.getFloat("volume"), speed = clip.getFloat("speed"),
                        x = clip.getFloat("x"), y = clip.getFloat("y"), color = clip.getInt("color"),
                        fontSize = clip.getFloat("fontSize"), brightness = clip.getFloat("brightness"),
                        contrast = clip.getFloat("contrast"), saturation = clip.getFloat("saturation"),
                        filterType = FilterType.valueOf(clip.getString("filterType")!!),
                        customLutId = clip.getString("customLutId"), customLutTitle = clip.getString("customLutTitle"),
                        aspectRatio = AspectRatio.valueOf(clip.getString("aspectRatio")!!),
                        originalAudioVolume = clip.getFloat("originalAudioVolume"), musicVolume = clip.getFloat("musicVolume"),
                    )
                })
            })
            require(timeline.tracks.sumOf { it.clips.size } <= 128)
            EditorDraftState(timeline, if (bundle.containsKey("selected")) bundle.getLong("selected") else null,
                bundle.getLong("position").coerceAtLeast(0), ExportQuality.valueOf(bundle.getString("quality")!!))
        }.getOrNull()
    }
}
