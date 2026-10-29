package com.example.witches

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.witches.core.Action
import com.example.witches.core.Deck
import com.example.witches.core.GameEngine
import com.example.witches.core.GameState
import com.example.witches.core.Phase
import com.example.witches.core.Transition
import com.example.witches.ui.MatchScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The same layout contracts run on the normal, compact/large-font and landscape device presets. */
class MatchLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Harness(initial: GameState) {
        var state = initial
            private set
        var game by mutableStateOf(GameEngine.view(initial))
            private set
        var computerMessage by mutableStateOf<String?>(null)

        fun publish(next: GameState) {
            check(GameEngine.validate(next))
            state = next
            game = GameEngine.view(next)
        }

        fun apply(action: Action) {
            publish((GameEngine.apply(state, action) as Transition.Accepted).state)
        }

        fun computerStep() {
            publish((GameEngine.computerStep(state) as Transition.Accepted).state)
        }
    }

    private fun show(initial: GameState): Harness {
        val landscape = InstrumentationRegistry.getArguments().getString("layoutOrientation") == "landscape"
        if (landscape) {
            compose.activityRule.scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(15_000) {
                compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            assertEquals("The requested test preset must actually be landscape",
                Configuration.ORIENTATION_LANDSCAPE, compose.activity.resources.configuration.orientation)
        }
        val harness = Harness(initial)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MatchScreen(harness.game, false, harness::apply, {}, {}, {}, {},
                    harness.computerMessage, Modifier.fillMaxSize().safeDrawingPadding(), reducedMotion = true)
            }
        }
        if (landscape) assertEquals("The layout host must remain landscape after composition",
            Configuration.ORIENTATION_LANDSCAPE, compose.activity.resources.configuration.orientation)
        return harness
    }

    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun assertSameY(expectedBoard: Rect, expectedHand: Rect) {
        compose.waitForIdle()
        val board = bounds("match-board")
        val hand = bounds("match-hand")
        assertEquals("The board top must stay fixed", expectedBoard.top, board.top, 1f)
        assertEquals("The board bottom must stay fixed", expectedBoard.bottom, board.bottom, 1f)
        assertEquals("The hand top must stay fixed", expectedHand.top, hand.top, 1f)
        assertEquals("The hand bottom must stay fixed", expectedHand.bottom, hand.bottom, 1f)
        compose.onNodeWithTag("match-board", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("match-hand", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("match-instructions", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun changingComputerPhrasesAndSelectionDoesNotMoveTheBoardOrHand() {
        val harness = show(GameEngine.newMatch("layout-messages", 7))
        val board = bounds("match-board")
        val hand = bounds("match-hand")
        val instructions = bounds("match-instructions")
        val deck = compose.onNodeWithContentDescription("Колода. Осталось 24 карт")
        val tableCard = compose.onNodeWithContentDescription("Стол, ряд 1, столбец 2. ", substring = true)
        val originalDeck = deck.fetchSemanticsNode().boundsInRoot
        val originalTableCard = tableCard.fetchSemanticsNode().boundsInRoot
        fun assertCardsStayFixed() {
            val nextDeck = deck.fetchSemanticsNode().boundsInRoot
            val nextTableCard = tableCard.fetchSemanticsNode().boundsInRoot
            assertEquals("The actual deck top must stay fixed", originalDeck.top, nextDeck.top, 1f)
            assertEquals("The actual deck bottom must stay fixed", originalDeck.bottom, nextDeck.bottom, 1f)
            assertEquals("The actual table card top must stay fixed", originalTableCard.top, nextTableCard.top, 1f)
            assertEquals("The actual table card bottom must stay fixed", originalTableCard.bottom, nextTableCard.bottom, 1f)
        }
        listOf("Вилл выбирает карту.", LONG_MESSAGE, null).forEach { phrase ->
            compose.runOnIdle { harness.computerMessage = phrase }
            assertSameY(board, hand)
            assertCardsStayFixed()
            val nextInstructions = bounds("match-instructions")
            assertEquals(instructions.top, nextInstructions.top, 1f)
            assertEquals(instructions.bottom, nextInstructions.bottom, 1f)
            if (phrase == LONG_MESSAGE) {
                val textArea = compose.onNodeWithTag("match-instructions", useUnmergedTree = true)
                val originalScroll = textArea.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                assertTrue("Long phrases must remain accessible by scrolling their own area", originalScroll.maxValue() > 0f)
                val scrollBeforeGesture = originalScroll.value()
                textArea.performTouchInput { swipeUp() }
                compose.waitForIdle()
                val scrollAfterGesture = textArea.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertTrue("The phrase itself must scroll", scrollAfterGesture > scrollBeforeGesture)
                assertSameY(board, hand)
                assertCardsStayFixed()
                textArea.performTouchInput { swipeDown() }
                assertSameY(board, hand)
                assertCardsStayFixed()
            }
        }

        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        val card = Deck.card(harness.game.hand.first()).type
        val description = "Рука. ${card.sign.label}. ${card.group.label}" +
            (card.guardian?.let { ". ${it.label}" } ?: "")
        compose.onAllNodesWithContentDescription(description).onFirst().performClick()
        compose.onNodeWithText("Выберите подсвеченную карту или пустое место").assertExists()
        assertSameY(board, hand)
        assertCardsStayFixed()
        compose.onAllNodesWithContentDescription(description).onFirst().performClick()
        compose.onNodeWithText("Выберите карту из руки").assertExists()
        assertSameY(board, hand)
        assertCardsStayFixed()
    }

    @Test fun originalTutorialPhasesKeepTheBoardAndHandAtTheSameY() {
        val harness = show(GameEngine.newMatch("layout-tutorial", 0, training = true))
        val board = bounds("match-board")
        val hand = bounds("match-hand")
        listOf(Phase.DRAW, Phase.DRAW_TARGET, Phase.HAND).forEach { phase ->
            compose.runOnIdle { harness.apply(GameEngine.legalActions(harness.state).first()) }
            assertEquals(phase, harness.game.phase)
            assertSameY(board, hand)
        }
        assertEquals(1, harness.game.actor)
        var steps = 0
        while (harness.state.activePlayer == 1 && steps++ < 8) {
            compose.runOnIdle {
                harness.computerStep()
                harness.computerMessage = if (steps % 2 == 0) LONG_MESSAGE else "Вилл собирает пару."
            }
            assertSameY(board, hand)
        }
        assertEquals("The source tutorial must return the turn to the player", 0, harness.game.actor)
        assertEquals(Phase.HAND, harness.game.phase)
        assertEquals(2, harness.game.ownCaptured.size)
        assertEquals(4, harness.game.opponentCaptured.size)
    }

    @Test fun emptyHandAndEndlessRoundSummaryDoNotMoveTheNextDeal() {
        val initial = GameEngine.newMatch("layout-endless", 7, target = null)
        val harness = show(initial)
        val board = bounds("match-board")
        val hand = bounds("match-hand")
        val endedRound = exhaustedRound(initial)
        compose.runOnIdle { harness.publish(endedRound) }
        assertTrue(harness.game.hand.isEmpty())
        assertEquals(Phase.ROUND_END, harness.game.phase)
        compose.onNodeWithText("Следующая раздача начнётся автоматически").assertExists()
        assertSameY(board, hand)
        compose.runOnIdle { harness.apply(Action.NextRound) }
        assertEquals(2, harness.game.round)
        assertEquals(8, harness.game.hand.size)
        assertEquals(Phase.HAND, harness.game.phase)
        assertSameY(board, hand)
    }

    @Test fun longEndlessScoresDoNotMoveTheBoardHandOrDeck() {
        val initial = GameEngine.newMatch("layout-long-scores", 7, target = null)
        val harness = show(initial)
        val board = bounds("match-board")
        val hand = bounds("match-hand")
        val deck = compose.onNodeWithContentDescription("Колода. Осталось 24 карт")
        val originalDeck = deck.fetchSemanticsNode().boundsInRoot
        listOf(2_147_483_700L, Long.MAX_VALUE, 0L).forEach { score ->
            // Layout-only fixture: valid endless state, with no move or awarded result fabricated.
            compose.runOnIdle { harness.publish(initial.copy(players = initial.players.map { it.copy(score = score) })) }
            compose.onNodeWithText("Вы $score · Вилл $score").assertExists()
            assertSameY(board, hand)
            val nextDeck = deck.fetchSemanticsNode().boundsInRoot
            assertEquals("The real deck top must not follow the score height", originalDeck.top, nextDeck.top, 1f)
            assertEquals("The real deck bottom must not follow the score height", originalDeck.bottom, nextDeck.bottom, 1f)
        }
    }

    @Test fun gesturesOnlyScrollTheHandHorizontally() {
        show(GameEngine.newMatch("layout-gestures", 7))
        val board = bounds("match-board")
        val hand = bounds("match-hand")
        val row = compose.onNode(hasScrollToIndexAction())
        val initialScroll = row.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        row.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val nextScroll = row.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertTrue("Horizontal gestures must still move the cards", nextScroll > initialScroll)
        assertSameY(board, hand)
        row.performTouchInput { swipeRight() }
        assertSameY(board, hand)
        listOf("match-board", "match-hand").forEach { tag ->
            compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput { swipeUp() }
            assertSameY(board, hand)
            compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput { swipeDown() }
            assertSameY(board, hand)
        }
    }

    private fun exhaustedRound(initial: GameState): GameState {
        var state = initial
        repeat(128) {
            if (state.phase == Phase.ROUND_END) {
                check(state.players.all { it.hand.isEmpty() })
                return state
            }
            val legal = GameEngine.legalActions(state)
            val action = if (Action.Continue in legal) Action.Continue
                else legal.first { it != Action.Announce && it != Action.EndSession }
            state = (GameEngine.apply(state, action) as Transition.Accepted).state
        }
        error("The real round did not finish within its bounded number of commands")
    }

    private companion object {
        val LONG_MESSAGE = ("Вилл разыгрывает открытую карту колоды и забирает совпавшую пару. " +
            "Собранные карты доступны в её наборах. Следите за знаком зодиака: разные категории " +
            "и стихии не мешают захвату. Эта длинная фраза должна прокручиваться внутри своего " +
            "блока, сохраняя положение игрового поля и карт в руке. ").repeat(6)
    }
}
