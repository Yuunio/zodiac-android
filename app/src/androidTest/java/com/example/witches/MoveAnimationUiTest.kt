package com.example.witches

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.witches.core.Action
import com.example.witches.core.Deck
import com.example.witches.core.GameEngine
import com.example.witches.core.GameView
import com.example.witches.core.Group
import com.example.witches.core.Phase
import com.example.witches.core.Transition
import com.example.witches.data.MovePresentation
import com.example.witches.data.presentMove
import com.example.witches.ui.MatchScreen
import com.example.witches.ui.ZodiacCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MoveAnimationUiTest {
    @get:Rule val compose = createComposeRule()

    private class Harness(val game: GameView, initial: MovePresentation?, val reducedMotion: Boolean) {
        var presentation by mutableStateOf(initial)
        var visible by mutableStateOf(true)
        val actions = mutableListOf<Action>()
        val completions = mutableListOf<Pair<String, Long>>()
        var pauses = 0
    }

    private fun show(game: GameView, presentation: MovePresentation? = null, reducedMotion: Boolean = false): Harness {
        val harness = Harness(game, presentation, reducedMotion)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (harness.visible) MatchScreen(harness.game, harness.presentation != null, { harness.actions += it },
                    { harness.pauses++ }, {}, {}, {}, null, Modifier.fillMaxSize(), harness.reducedMotion,
                    harness.presentation, { id, revision ->
                        harness.completions += id to revision
                        if (harness.presentation?.key == "$id:$revision") harness.presentation = null
                    })
                else Text("Меню")
            }
        }
        return harness
    }

    private fun capture(): MovePresentation {
        val before = GameEngine.newMatch("motion-capture", 12, training = true)
        val after = (GameEngine.apply(before, GameEngine.legalActions(before).single()) as Transition.Accepted).state
        return requireNotNull(presentMove(GameEngine.view(before), GameEngine.view(after)))
    }

    @Test fun captureMovesAcrossIntermediatePositionsAndCompletesWithoutApplyingAnotherAction() {
        compose.mainClock.autoAdvance = false
        val presentation = capture()
        val harness = show(presentation.after, presentation)
        compose.mainClock.advanceTimeBy(32)
        val initial = compose.onNodeWithTag("move-card-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("Колода. Осталось 24 карт").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(120)
        val intermediate = compose.onNodeWithTag("move-card-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("The captured card must travel before it disappears", (initial.center - intermediate.center).getDistance() > 5f)
        assertEquals(0, compose.onAllNodesWithContentDescription("Стрелец. Огонь", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("move-card-0", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(presentation.after.matchId to presentation.after.revision), harness.completions)
            assertTrue(harness.actions.isEmpty())
            assertEquals(presentation.after, harness.game)
        }
    }

    @Test fun reducedMotionSkipsFlightsAndOnlyAcknowledgesTheCommittedRevision() {
        val presentation = capture()
        val harness = show(presentation.after, presentation, reducedMotion = true)
        compose.onNodeWithTag("move-card-0", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(presentation.after.matchId to presentation.after.revision), harness.completions)
            assertTrue(harness.actions.isEmpty())
            assertEquals(presentation.after, harness.game)
        }
    }

    @Test fun pauseRemainsAvailableDuringAFlightAndCancelsOnlyItsPresentation() {
        compose.mainClock.autoAdvance = false
        val presentation = capture()
        val harness = show(presentation.after, presentation)
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("Пауза").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("move-card-0", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, harness.pauses)
            assertEquals(listOf(presentation.after.matchId to presentation.after.revision), harness.completions)
            assertTrue(harness.actions.isEmpty())
            assertEquals(presentation.after, harness.game)
        }
    }

    @Test fun removingTheScreenCompletesThePendingPresentationWithoutReplayingIt() {
        compose.mainClock.autoAdvance = false
        val presentation = capture()
        val harness = show(presentation.after, presentation)
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { harness.visible = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("Меню").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(presentation.after.matchId to presentation.after.revision), harness.completions)
            assertTrue(harness.actions.isEmpty())
        }
    }

    @Test fun selectingACardLiftsAndScalesItBeforeSettling() {
        compose.mainClock.autoAdvance = false
        var selected by mutableStateOf(false)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.padding(24.dp)) {
                    ZodiacCard(7, selected, true, { selected = true }, {}, Modifier.size(70.dp, 110.dp).testTag("selection-card"), selectedLift = selected)
                }
            }
        }
        val original = compose.onNodeWithTag("selection-card").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("selection-card").performClick()
        compose.mainClock.advanceTimeBy(80)
        val intermediate = compose.onNodeWithTag("selection-card").fetchSemanticsNode().boundsInRoot
        assertTrue(intermediate.top < original.top)
        assertTrue(intermediate.width > original.width)
        compose.mainClock.advanceTimeBy(180)
        compose.onNodeWithTag("selection-card").assertIsDisplayed()
    }

    @Test fun endlessRoundUsesAnAutomaticSummaryInsteadOfABlockingResult() {
        val base = GameEngine.view(GameEngine.newMatch("endless-ui", 2, target = null)).copy(
            phase = Phase.ROUND_END, actor = 1, scores = listOf(2_147_483_700L, 70L),
            lastAward = 70, roundWinner = 1,
            opponentCaptured = Deck.cards.filter { it.type.group == Group.FIRE }.map { it.id },
        )
        show(base)
        compose.onNodeWithText("Следующая раздача начнётся автоматически").assertIsDisplayed()
        compose.onNodeWithText("Вилл объявила комбинации").assertDoesNotExist()
        compose.onNodeWithText("Следующий розыгрыш").assertDoesNotExist()
    }

    @Test fun endedEndlessSessionShowsLongTotalsAndNoWinner() {
        val initial = GameEngine.newMatch("endless-ended-ui", 3, target = null).let { state ->
            state.copy(players = state.players.map { it.copy(score = 2_147_483_700L) })
        }
        val ended = (GameEngine.apply(initial, Action.EndSession) as Transition.Accepted).state
        show(GameEngine.view(ended))
        compose.onNodeWithText("Бесконечная игра завершена").assertIsDisplayed()
        compose.onNodeWithText("Счёт: 2147483700 — 2147483700.").assertIsDisplayed()
        compose.onNodeWithText("Розыгрышей: 1. Победитель в этом режиме не определяется.").assertIsDisplayed()
        compose.onNodeWithText("Победила Вилл").assertDoesNotExist()
    }
}
