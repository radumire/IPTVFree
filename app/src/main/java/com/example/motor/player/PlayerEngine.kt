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
    val isNone: Boolean = false
)

// ─── PLAYER ENGINE UTILITIES ──────────────────────────────────────────────────

object PlayerEngine {
    
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
                        val label = getReadableLanguageName(format.language, format.label)
                        val isSelected = !player.trackSelectionParameters.disabledTrackTypes.contains(type) && group.isTrackSelected(trackIndex)
                        
                        options.add(
                            TrackOption(
                                id = "${groupIndex}_${trackIndex}",
                                name = label,
                                type = type,
                                groupIndex = groupIndex,
                                trackIndex = trackIndex,
                                isSelected = isSelected
                            )
                        )
                    }
                }
            }
        }
        return options
    }

    fun selectTrack(player: Player, option: TrackOption) {
        if (option.isNone) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(option.type, true)
                .build()
        } else {
            val tracks = player.currentTracks
            if (option.groupIndex >= 0 && option.groupIndex < tracks.groups.size) {
                val group = tracks.groups[option.groupIndex]
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(option.type, false)
                    .setOverrideForType(
                        TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex)
                    )
                    .build()
            }
        }
    }
}
