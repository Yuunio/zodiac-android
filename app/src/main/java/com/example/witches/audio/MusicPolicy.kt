package com.example.witches.audio

enum class MusicTrack { MENU, GAME }

internal data class MusicRequest(
    val track: MusicTrack = MusicTrack.MENU,
    val enabled: Boolean = false,
    val volume: Float = .35f,
    val active: Boolean = false,
)

internal data class MusicDecision(
    val play: Boolean = false,
    val requestFocus: Boolean = false,
    val abandonFocus: Boolean = false,
)

/** Keeps a denied focus request or unplug event from restarting audio on recomposition. */
internal class MusicPolicy {
    private enum class Focus { NONE, HELD, WAITING }

    var request = MusicRequest()
        private set
    private var focus = Focus.NONE
    private var blocked = false

    fun update(track: MusicTrack, enabled: Boolean, volume: Float, active: Boolean): MusicDecision {
        val safeVolume = if (volume.isFinite()) volume.coerceIn(0f, 1f) else 0f
        if ((!request.active && active) || (!request.enabled && enabled) ||
            (request.volume == 0f && safeVolume > 0f)) blocked = false
        request = MusicRequest(track, enabled, safeVolume, active)
        return decision()
    }

    fun focusResult(granted: Boolean): MusicDecision {
        focus = if (granted) Focus.HELD else Focus.NONE
        blocked = !granted
        return decision()
    }

    fun focusGained(): MusicDecision {
        focus = Focus.HELD
        return decision()
    }

    fun focusLost(temporary: Boolean): MusicDecision {
        if (temporary) focus = Focus.WAITING else blocked = true
        return decision()
    }

    fun interrupt(): MusicDecision {
        blocked = true
        return decision()
    }

    fun decision(): MusicDecision {
        if (!request.enabled || !request.active || request.volume == 0f || blocked) {
            val abandon = focus != Focus.NONE
            focus = Focus.NONE
            return MusicDecision(abandonFocus = abandon)
        }
        return MusicDecision(play = focus == Focus.HELD, requestFocus = focus == Focus.NONE)
    }
}
