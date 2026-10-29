package com.example.witches.core

import org.junit.Assert.*
import org.junit.Test

class TutorialGuideTest {
    private data class Golden(
        val step: Int, val actor: Int, val phase: Phase,
        val hand: String? = null, val target: String? = null, val drawn: String? = null,
    )

    // Source party steps 1..14 plus the independently read CompTurn/CompCardFromPack order.
    private val script = listOf(
        Golden(1, 0, Phase.HAND, hand = "sagittarius_fire", target = "sagittarius_square"),
        Golden(2, 0, Phase.DRAW),
        Golden(2, 0, Phase.DRAW_TARGET, drawn = "aquarius_air"),
        Golden(3, 1, Phase.HAND, hand = "pisces", target = "pisces_water"),
        Golden(4, 1, Phase.DRAW),
        Golden(4, 1, Phase.DRAW_TARGET, drawn = "capricorn", target = "capricorn_triangle"),
        Golden(5, 0, Phase.HAND, hand = "cancer_square", target = "cancer"),
        Golden(6, 0, Phase.DRAW),
        Golden(6, 0, Phase.DRAW_TARGET, drawn = "aries", target = "aries_fire"),
        Golden(7, 1, Phase.HAND, hand = "virgo", target = "virgo_square"),
        Golden(8, 1, Phase.DRAW),
        Golden(8, 1, Phase.DRAW_TARGET, drawn = "aries"),
        Golden(9, 0, Phase.HAND, hand = "capricorn"),
        Golden(10, 0, Phase.DRAW),
        Golden(10, 0, Phase.DRAW_TARGET, drawn = "leo_fire"),
        Golden(11, 1, Phase.HAND, hand = "aries_triangle", target = "aries"),
        Golden(12, 1, Phase.DRAW),
        Golden(12, 1, Phase.DRAW_TARGET, drawn = "libra", target = "libra_pentagon"),
        Golden(13, 0, Phase.HAND, hand = "leo", target = "leo_fire"),
        Golden(14, 0, Phase.DECLARE),
    )

    @Test fun originalFourteenStepsRestrictSelectionAndMatchEveryIntermediateCapture() {
        var state = GameEngine.newMatch("source-tutorial", 72, training = true)
        val captures = List(2) { mutableListOf<String>() }
        script.forEach { golden ->
            assertEquals(golden.phase, state.phase)
            assertEquals(golden.actor, state.activePlayer)
            val step = requireNotNull(GameEngine.tutorialStep(state))
            assertEquals(golden.step, step.index)
            assertEquals(golden.actor, step.actor)
            assertFalse(step.restartRequired)
            assertTrue(step.instruction.isNotBlank())
            assertEquals(step, GameEngine.tutorialStep(state.copy(revision = state.revision + 1000)))
            assertEquals(golden.drawn, state.drawnCard?.let { Deck.card(it).type.key })
            if (golden.actor == 0 && golden.phase == Phase.HAND) {
                assertEquals(golden.hand, step.expectedCardId?.let { Deck.card(it).type.key })
                assertEquals(golden.target, step.expectedTargetId?.let { Deck.card(it).type.key })
                val legal = GameEngine.legalActions(state).filterIsInstance<Action.Play>()
                assertTrue(legal.isNotEmpty())
                assertTrue(legal.all { Deck.card(it.cardId).type.key == golden.hand })
                if (golden.target != null) assertTrue(legal.all { state.board[it.slot]?.let(Deck::card)?.type?.key == golden.target })
                else assertTrue(legal.all { state.board.getOrNull(it.slot) == null })
                val wrong = state.players[0].hand.first { Deck.card(it).type.key != golden.hand }
                assertTrue(GameEngine.apply(state, Action.Play(wrong, 9)) is Transition.Rejected)
            }
            val before = state
            val action = action(state, golden)
            val result = if (golden.actor == 1) GameEngine.computerStep(state) else GameEngine.apply(state, action)
            assertTrue("$golden: $result", result is Transition.Accepted)
            state = (result as Transition.Accepted).state
            if (golden.phase == Phase.HAND) {
                val removed = before.players[golden.actor].hand - state.players[golden.actor].hand.toSet()
                assertEquals(listOf(golden.hand), removed.map { Deck.card(it).type.key })
            }
            if (golden.target != null) captures[golden.actor].addAll(listOf(requireNotNull(golden.hand ?: golden.drawn), golden.target))
            assertEquals(captures[0].sorted(), state.players[0].captured.map { Deck.card(it).type.key }.sorted())
            assertEquals(captures[1].sorted(), state.players[1].captured.map { Deck.card(it).type.key }.sorted())
            assertTrue(GameEngine.validate(state))
        }
        assertEquals(Phase.MATCH_END, state.phase)
        assertEquals(70L, state.players[0].score)
        assertEquals(18, state.deck.size)
        assertEquals(listOf("aquarius_air", "capricorn", "gemini"), state.board.filterNotNull().map { Deck.card(it).type.key }.sorted())
    }

    @Test fun wrongOldTrainingSnapshotRequiresExplicitRestartRatherThanGuessingByHandCount() {
        val start = GameEngine.newMatch("old-training", 41, training = true)
        val ordinary = start.copy(training = false)
        val wrongCard = ordinary.players[0].hand.first { Deck.card(it).type.key == "aquarius_pentagon" }
        val wrongAction = GameEngine.legalActions(ordinary).filterIsInstance<Action.Play>().first { it.cardId == wrongCard }
        val oldSnapshot = (GameEngine.apply(ordinary, wrongAction) as Transition.Accepted).state.copy(training = true)
        val correctAction = GameEngine.legalActions(start).single()
        val correct = (GameEngine.apply(start, correctAction) as Transition.Accepted).state
        assertEquals(correct.players[0].hand.size, oldSnapshot.players[0].hand.size)
        assertEquals(correct.phase, oldSnapshot.phase)
        assertEquals(2, GameEngine.tutorialStep(correct)?.index)
        val incompatible = requireNotNull(GameEngine.tutorialStep(oldSnapshot))
        assertEquals(0, incompatible.index)
        assertTrue(incompatible.restartRequired)
        assertTrue(GameEngine.legalActions(oldSnapshot).isEmpty())
        val result = GameEngine.apply(oldSnapshot, Action.Draw)
        assertEquals(Transition.Rejected(incompatible.instruction), result)
        assertTrue(GameEngine.validate(oldSnapshot))
        assertEquals(1, GameEngine.tutorialStep(GameEngine.newMatch("restart", 41, training = true))?.index)
    }

    @Test fun ordinaryMatchKeepsItsFreedomAndTutorialNeverExposesTheOtherHand() {
        val guided = GameEngine.newMatch("guided", 10, training = true)
        val ordinary = guided.copy(training = false)
        assertNull(GameEngine.tutorialStep(ordinary))
        assertTrue(GameEngine.legalActions(ordinary).size > GameEngine.legalActions(guided).size)
        assertEquals(1, GameEngine.legalActions(guided).size)
        assertNull(GameEngine.tutorialStep(GameEngine.view(guided, 1))?.expectedCardId)
        assertTrue(GameEngine.legalActions(GameEngine.view(guided, 1)).isEmpty())
    }

    @Test fun capricornPlacementIsFreeAndFinalTutorialRefusalIsBlocked() {
        var state = GameEngine.newMatch("free-placement", 54, training = true)
        script.forEach { golden ->
            if (golden.step == 9) {
                val legal = GameEngine.legalActions(state).filterIsInstance<Action.Play>()
                assertTrue(legal.size > 1)
                legal.forEach { candidate ->
                    val placed = (GameEngine.apply(state, candidate) as Transition.Accepted).state
                    assertEquals(10, GameEngine.tutorialStep(placed)?.index)
                    assertFalse(requireNotNull(GameEngine.tutorialStep(placed)).restartRequired)
                }
            }
            if (golden.phase == Phase.DECLARE) {
                assertEquals(listOf(Action.Announce), GameEngine.legalActions(state))
                assertTrue(GameEngine.apply(state, Action.Continue) is Transition.Rejected)
                val ordinary = state.copy(training = false, target = 900)
                assertTrue(Action.Continue in GameEngine.legalActions(ordinary))
                assertTrue(GameEngine.apply(ordinary, Action.Continue) is Transition.Accepted)
            }
            val result = if (golden.actor == 1) GameEngine.computerStep(state) else GameEngine.apply(state, action(state, golden))
            state = (result as Transition.Accepted).state
        }
    }

    private fun action(state: GameState, golden: Golden): Action = when (golden.phase) {
        Phase.HAND -> {
            val id = state.players[golden.actor].hand.first { Deck.card(it).type.key == golden.hand }
            val legal = GameEngine.legalActions(state).filterIsInstance<Action.Play>().filter { it.cardId == id }
            if (golden.target == null) legal.last() else legal.first { state.board[it.slot]?.let(Deck::card)?.type?.key == golden.target }
        }
        Phase.DRAW -> Action.Draw
        Phase.DRAW_TARGET -> {
            val legal = GameEngine.legalActions(state).filterIsInstance<Action.ResolveDraw>()
            if (golden.target == null) legal.last() else legal.first { state.board[it.slot]?.let(Deck::card)?.type?.key == golden.target }
        }
        Phase.DECLARE -> Action.Announce
        Phase.ROUND_END, Phase.MATCH_END, Phase.SESSION_END -> error("В исходном учебном сценарии здесь нет игрового действия")
    }
}
