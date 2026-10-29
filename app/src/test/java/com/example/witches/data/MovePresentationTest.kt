package com.example.witches.data

import com.example.witches.core.*
import org.junit.Assert.*
import org.junit.Test

class MovePresentationTest {
    @Test fun committedTutorialPairAndDrawHaveOnlyPublicIdentities() {
        val before = GameEngine.newMatch("motion", 1, training = true)
        val action = GameEngine.legalActions(before).first()
        val captured = (GameEngine.apply(before, action) as Transition.Accepted).state
        val pair = requireNotNull(presentMove(GameEngine.view(before), GameEngine.view(captured)))
        assertEquals(MoveKind.CAPTURE, pair.kind)
        assertTrue(pair.cardId in captured.players[0].captured)
        assertTrue(pair.pairedCardId in before.board)
        assertFalse(pair.fromDeck)
        val drawn = (GameEngine.apply(captured, Action.Draw) as Transition.Accepted).state
        val reveal = requireNotNull(presentMove(GameEngine.view(captured), GameEngine.view(drawn)))
        assertEquals(MoveKind.DRAW, reveal.kind)
        assertEquals(drawn.drawnCard, reveal.cardId)
        assertEquals(4, reveal.slot)
    }

    @Test fun foreignStaleAndOpponentViewsNeverProduceAnimation() {
        val game = GameEngine.newMatch("motion", 1)
        val view = GameEngine.view(game)
        assertNull(presentMove(view, view))
        assertNull(presentMove(view, view.copy(matchId = "other", revision = 1)))
        assertNull(presentMove(view, view.copy(revision = 2)))
        assertNull(presentMove(view.copy(viewer = 1), view.copy(revision = 1)))
    }

    @Test fun opponentAnimationIsDerivedOnlyFromTheNewPublicPair() {
        var state = GameEngine.newMatch("tutorial", 1, training = true)
        while (state.activePlayer == 0) {
            state = (GameEngine.apply(state, GameEngine.legalActions(state).first()) as Transition.Accepted).state
        }
        val next = (GameEngine.computerStep(state) as Transition.Accepted).state
        val animation = requireNotNull(presentMove(GameEngine.view(state), GameEngine.view(next)))
        assertEquals(1, animation.actor)
        assertEquals(MoveKind.CAPTURE, animation.kind)
        assertTrue(animation.cardId in next.players[1].captured)
        assertTrue(animation.pairedCardId in next.players[1].captured)
        assertEquals(2, next.players[1].captured.size)
    }
}
