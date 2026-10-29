package com.example.witches.data

import com.example.witches.core.GameView
import com.example.witches.core.Phase

enum class MoveKind(val durationMillis: Int) {
    DEAL(900), PLACE(250), CAPTURE(450), DRAW(240), ANNOUNCE(650), TURN(150)
}

/** Only viewer-zero information from a committed transition; never a hidden hand or deck. */
data class MovePresentation(
    val after: GameView,
    val kind: MoveKind,
    val actor: Int = 0,
    val cardId: Int? = null,
    val slot: Int? = null,
    val pairedCardId: Int? = null,
    val fromDeck: Boolean = false,
) {
    val key: String get() = "${after.matchId}:${after.revision}"
}

fun presentMove(before: GameView, after: GameView): MovePresentation? {
    if (before.viewer != 0 || after.viewer != 0 || before.matchId != after.matchId ||
        after.revision != before.revision + 1) return null
    if (after.round != before.round) return MovePresentation(after, MoveKind.DEAL)
    if (before.phase == Phase.DRAW && after.drawnCard != null) {
        return MovePresentation(after, MoveKind.DRAW, before.actor, after.drawnCard, 4, fromDeck = true)
    }
    if (before.phase == Phase.HAND || before.phase == Phase.DRAW_TARGET) {
        val oldPool = if (before.actor == 0) before.ownCaptured else before.opponentCaptured
        val newPool = if (before.actor == 0) after.ownCaptured else after.opponentCaptured
        val gained = newPool.filterNot { it in oldPool }
        if (gained.size == 2) {
            val slot = before.board.indexOfFirst { it in gained }
            if (slot < 0) return null
            val paired = before.board[slot]
            return MovePresentation(after, MoveKind.CAPTURE, before.actor,
                gained.first { it != paired }, slot, paired, before.phase == Phase.DRAW_TARGET)
        }
        val slot = after.board.indices.firstOrNull {
            after.board[it] != null && before.board.getOrNull(it) == null
        }
        if (slot != null) return MovePresentation(after, MoveKind.PLACE, before.actor,
            after.board[slot], slot, fromDeck = before.phase == Phase.DRAW_TARGET)
    }
    return MovePresentation(after, if (after.scores != before.scores) MoveKind.ANNOUNCE else MoveKind.TURN, before.actor)
}
