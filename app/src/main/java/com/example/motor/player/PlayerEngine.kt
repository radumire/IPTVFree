package com.example.motor.player

import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride

// ─── TRACK OPTION MODEL ──────────────────────────────────────────────────────

data class TrackOption(
    val id: String,
    val name: String,
    val type: Int,
    val groupIndex: Int,
    val trackIndex: Int,
    val isSelected: Boolean,
    val isNone: Boolean = false,
    val formatId: String? = null,
    val formatLabel: String? = null,
    val externalSubtitleId: String? = null
)

// ─── PLAYER ENGINE UTILITIES ──────────────────────────────────────────────────

object PlayerEngine {
    
    private const val TAG_SUBTITLES = "SUBTITLE_TRACKS"

    fun getReadableLanguageName(code: String?, label: String?): String {
        if (!label.isNullOrBlank()) return label
        if (code.isNullOrBlank()) return "Desconocido"
        return when (code.lowercase().trim()) {
            "spa", "es", "spanish" -> "Español"
            "eng", "en", "english" -> "Inglés"
            "fre", "fra", "fr", "french" -> "Francés"
            "ger", "deu", "de", "german" -> "Alemán"
            "ita", "it", "italian" -> "Italiano"
            "por", "pt", "portuguese" -> "Portugués"
            "cat", "ca", "catalan" -> "Catalán"
            "glg", "gl", "galician" -> "Gallego"
            "eus", "eu", "basque" -> "Euskera"
            "rum", "ron", "ro", "romanian" -> "Rumano"
            "rus", "ru", "russian" -> "Ruso"
            "und", "undetermined" -> "Pista (Predeterminada)"
            else -> code.uppercase()
        }
    }

    fun getTrackOptions(player: Player, type: Int): List<TrackOption> {
        val options = mutableListOf<TrackOption>()
        val tracks = player.currentTracks
        
        var anySelected = false
        for (groupIndex in 0 until tracks.groups.size) {
            val group = tracks.groups[groupIndex]
            if (group.type == type && group.isSupported) {
                for (trackIndex in 0 until group.length) {
                    if (group.isTrackSelected(trackIndex)) {
                        anySelected = true
                    }
                }
            }
        }

        if (type == androidx.media3.common.C.TRACK_TYPE_TEXT) {
            val isNoneSelected = player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT) || !anySelected
            options.add(
                TrackOption(
                    id = "none",
                    name = "Desactivados",
                    type = type,
                    groupIndex = -1,
                    trackIndex = -1,
                    isSelected = isNoneSelected,
                    isNone = true
                )
            )
        }

        for (groupIndex in 0 until tracks.groups.size) {
            val group = tracks.groups[groupIndex]
            if (group.type == type && group.isSupported) {
                for (trackIndex in 0 until group.length) {
                    if (group.isTrackSupported(trackIndex)) {
                        val format = group.getTrackFormat(trackIndex)
                        val label = if (!format.label.isNullOrBlank()) {
                            format.label!!
                        } else {
                            getReadableLanguageName(format.language, format.label)
                        }
                        val isSelected = !player.trackSelectionParameters.disabledTrackTypes.contains(type) && group.isTrackSelected(trackIndex)
                        val trackId = format.id ?: "${groupIndex}_${trackIndex}"
                        
                        val opt = TrackOption(
                            id = trackId,
                            name = label,
                            type = type,
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                            isSelected = isSelected,
                            formatId = format.id,
                            formatLabel = format.label,
                            externalSubtitleId = format.id
                        )
                        options.add(opt)

                        if (type == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                            android.util.Log.i(
                                TAG_SUBTITLES,
                                "SUBTITLE_TRACKS_LOADED externalId=${format.id} group=$groupIndex track=$trackIndex label=\"$label\""
                            )
                        }
                    }
                }
            }
        }
        return options
    }

    fun selectTrack(player: Player, option: TrackOption) {
        if (option.isNone) {
            android.util.Log.i(TAG_SUBTITLES, "SUBTITLE_TRACK_DISABLED")
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(option.type, true)
                .clearOverridesOfType(option.type)
                .build()
        } else {
            val tracks = player.currentTracks
            if (option.groupIndex >= 0 && option.groupIndex < tracks.groups.size) {
                val group = tracks.groups[option.groupIndex]
                if (option.trackIndex >= 0 && option.trackIndex < group.length) {
                    android.util.Log.i(
                        TAG_SUBTITLES,
                        "SUBTITLE_TRACK_SELECTED id=${option.id} group=${option.groupIndex} track=${option.trackIndex} label=\"${option.name}\""
                    )
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(option.type, false)
                        .setOverrideForType(
                            TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex)
                        )
                        .build()
                    return
                }
            }
            android.util.Log.w(TAG_SUBTITLES, "SUBTITLE_TRACK_NOT_FOUND id=${option.id}")
        }
    }
}
