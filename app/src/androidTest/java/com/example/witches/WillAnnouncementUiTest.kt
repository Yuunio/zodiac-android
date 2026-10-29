package com.example.witches

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.witches.core.Action
import com.example.witches.core.Deck
import com.example.witches.core.GameView
import com.example.witches.core.Group
import com.example.witches.core.Phase
import com.example.witches.ui.MatchScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WillAnnouncementUiTest {
    @get:Rule val compose = createComposeRule()

    private class Harness(initial: GameView) {
        var game by mutableStateOf(initial)
        val actions = mutableListOf<Action>()
        val newGames = mutableListOf<Boolean>()
    }

    private fun show(initial: GameView, restoration: StateRestorationTester? = null): Harness {
        val harness = Harness(initial)
        val content: @Composable () -> Unit = {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MatchScreen(harness.game, false, { harness.actions += it }, {}, {},
                    { harness.newGames += it }, {}, null, Modifier.fillMaxSize())
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        return harness
    }

    @Test fun acknowledgementShowsResultWithoutApplyingACommandOrChangingTheAward() {
        val initial = result()
        val harness = show(initial)
        compose.onNodeWithText("Вилл объявила комбинации").assertIsDisplayed()
        compose.onNodeWithText("Огонь: 70").assertIsDisplayed()
        compose.onNodeWithText("Сумма объявления: 70 очков.").assertIsDisplayed()
        compose.onNodeWithText("Следующий розыгрыш").assertDoesNotExist()
        compose.onNodeWithText("Показать итог").performClick()
        compose.onNodeWithText("Вилл объявила комбинации").assertDoesNotExist()
        compose.onNodeWithText("Счёт: 0 — 70.").assertIsDisplayed()
        compose.onNodeWithText("Следующий розыгрыш").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(harness.actions.isEmpty())
            assertEquals(initial, harness.game)
        }
        compose.onNodeWithText("Следующий розыгрыш").performClick()
        compose.runOnIdle { assertEquals(listOf(Action.NextRound), harness.actions) }
    }

    @Test fun pendingAndAcknowledgedAnnouncementSurviveSavedUiStateRestoration() {
        val restoration = StateRestorationTester(compose)
        val initial = result()
        val harness = show(initial, restoration)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Вилл объявила комбинации").assertIsDisplayed()
        compose.onNodeWithText("Показать итог").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Вилл объявила комбинации").assertDoesNotExist()
        compose.onNodeWithText("Следующий розыгрыш").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(initial, harness.game)
            assertTrue(harness.actions.isEmpty())
        }
    }

    @Test fun bothNewRevisionAndNewMatchRequireTheirOwnAcknowledgement() {
        val harness = show(result())
        compose.onNodeWithText("Показать итог").performClick()
        compose.runOnIdle { harness.game = harness.game.copy(revision = 11, round = 2) }
        compose.onNodeWithText("Вилл объявила комбинации").assertIsDisplayed()
        compose.onNodeWithText("Следующий розыгрыш").assertDoesNotExist()
        compose.onNodeWithText("Показать итог").performClick()
        compose.runOnIdle { harness.game = harness.game.copy(matchId = "another-match") }
        compose.onNodeWithText("Вилл объявила комбинации").assertIsDisplayed()
        compose.onNodeWithText("Следующий розыгрыш").assertDoesNotExist()
        compose.runOnIdle { assertTrue(harness.actions.isEmpty()) }
    }

    @Test fun winningWillAnnouncementPrecedesTheMatchResult() {
        val initial = result().copy(phase = Phase.MATCH_END, scores = listOf(0L, 900L), winner = 1)
        val harness = show(initial)
        compose.onNodeWithText("Вилл объявила комбинации").assertIsDisplayed()
        compose.onNodeWithText("Победила Вилл").assertDoesNotExist()
        compose.onNodeWithText("Новая игра").assertDoesNotExist()
        compose.onNodeWithText("Показать итог").performClick()
        compose.onNodeWithText("Победила Вилл").assertIsDisplayed()
        compose.onNodeWithText("Счёт: 0 — 900.").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(initial, harness.game)
            assertTrue(harness.actions.isEmpty())
        }
        compose.onNodeWithText("Новая игра").performClick()
        compose.runOnIdle { assertEquals(listOf(false), harness.newGames) }
    }

    @Test fun playerAnnouncementShowsTheExistingResultDirectly() {
        val initial = result().let { it.copy(actor = 0, ownCaptured = it.opponentCaptured,
            opponentCaptured = emptyList(), scores = listOf(70L, 0L), roundWinner = 0) }
        val harness = show(initial)
        compose.onNodeWithText("Вилл объявила комбинации").assertDoesNotExist()
        compose.onNodeWithText("Показать итог").assertDoesNotExist()
        compose.onNodeWithText("Вы объявили комбинации: +70.").assertIsDisplayed()
        compose.onNodeWithText("Следующий розыгрыш").assertIsDisplayed()
        compose.runOnIdle { assertEquals(initial, harness.game); assertTrue(harness.actions.isEmpty()) }
    }

    @Test fun roundWithoutAnAnnouncementDoesNotRequestAcknowledgement() {
        val initial = result().copy(actor = 0, opponentCaptured = emptyList(), scores = listOf(0L, 0L),
            lastAward = 0, roundWinner = null)
        val harness = show(initial)
        compose.onNodeWithText("Вилл объявила комбинации").assertDoesNotExist()
        compose.onNodeWithText("Показать итог").assertDoesNotExist()
        compose.onNodeWithText("Счёт: 0 — 0.").assertIsDisplayed()
        compose.onNodeWithText("Следующий розыгрыш").assertIsDisplayed()
        compose.runOnIdle { assertEquals(initial, harness.game); assertTrue(harness.actions.isEmpty()) }
    }

    private fun result() = GameView(
        matchId = "will-announcement", revision = 10, round = 1, target = 900, actor = 1,
        phase = Phase.ROUND_END, hand = emptyList(), opponentHandCount = 0,
        board = List(15) { null }, deckCount = 0, drawnCard = null, ownCaptured = emptyList(),
        opponentCaptured = Deck.cards.filter { it.type.group == Group.FIRE }.map { it.id },
        scores = listOf(0L, 70L), lastAward = 70, roundWinner = 1, winner = null,
        training = false, aiDenominator = 3,
    )
}
