package com.example.witches

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.witches.core.Difficulty
import com.example.witches.core.GameEngine
import com.example.witches.core.Phase
import com.example.witches.data.CampaignProgress
import com.example.witches.data.PrototypeUiState
import com.example.witches.data.SessionConfig
import com.example.witches.data.SessionSlot
import com.example.witches.data.SessionView
import com.example.witches.ui.CampaignScreen
import com.example.witches.ui.EndlessScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CampaignUiTest {
    @get:Rule val compose = createComposeRule()

    private class Harness(initial: PrototypeUiState, initialSlot: SessionSlot) {
        var state by mutableStateOf(initial)
        var slot by mutableStateOf(initialSlot)
        val starts = mutableListOf<SessionConfig>()
        val resumes = mutableListOf<SessionSlot>()
    }

    private fun show(
        progress: CampaignProgress = CampaignProgress(),
        slot: SessionSlot = SessionSlot.CAMPAIGN,
        sessions: Map<SessionSlot, SessionView> = emptyMap(),
        busy: Boolean = false,
    ): Harness {
        val harness = Harness(PrototypeUiState(loaded = true, progress = progress, sessions = sessions, busy = busy), slot)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                when (harness.slot) {
                    SessionSlot.CAMPAIGN -> CampaignScreen(harness.state, { harness.starts += it },
                        { harness.resumes += SessionSlot.CAMPAIGN }, {}, Modifier.fillMaxSize())
                    SessionSlot.ENDLESS -> EndlessScreen(harness.state, { harness.starts += it },
                        { harness.resumes += SessionSlot.ENDLESS }, {}, Modifier.fillMaxSize())
                    SessionSlot.AUXILIARY -> error("В этих тестах только два игровых режима")
                }
            }
        }
        return harness
    }

    private fun campaign(level: Int, difficulty: Difficulty, status: String) =
        compose.onNodeWithContentDescription("Уровень $level. ${difficulty.label}. $status")

    private fun endless(difficulty: Difficulty) =
        compose.onNodeWithContentDescription("Бесконечная игра. ${difficulty.label}")

    @Test fun firstCampaignOffersOnlyLevelOneEasyAndEmitsItsConfiguration() {
        val harness = show()
        campaign(1, Difficulty.EASY, "Доступно").assertIsEnabled()
        campaign(1, Difficulty.NORMAL, "Закрыто").assertIsNotEnabled()
        campaign(1, Difficulty.HARD, "Закрыто").assertIsNotEnabled()
        campaign(2, Difficulty.EASY, "Закрыто").assertIsNotEnabled()
        campaign(1, Difficulty.EASY, "Доступно").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(SessionConfig(SessionSlot.CAMPAIGN, Difficulty.EASY, 1)), harness.starts)
            assertTrue(harness.resumes.isEmpty())
        }
    }

    @Test fun easyVictoryOpensCurrentMediumAndNextEasyWithoutOpeningHard() {
        val harness = show(CampaignProgress().withVictory(1, Difficulty.EASY))
        campaign(1, Difficulty.EASY, "Пройдено").assertIsEnabled()
        campaign(1, Difficulty.NORMAL, "Доступно").performScrollTo().assertIsEnabled().performClick()
        campaign(1, Difficulty.HARD, "Закрыто").assertIsNotEnabled()
        campaign(2, Difficulty.EASY, "Доступно").performScrollTo().assertIsEnabled().performClick()
        campaign(2, Difficulty.NORMAL, "Закрыто").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf(SessionConfig(SessionSlot.CAMPAIGN, Difficulty.NORMAL, 1),
                SessionConfig(SessionSlot.CAMPAIGN, Difficulty.EASY, 2)), harness.starts)
        }
    }

    @Test fun mediumVictoryOpensHardAndCompletedDifficultyCanBeReplayed() {
        val progress = CampaignProgress().withVictory(1, Difficulty.EASY).withVictory(1, Difficulty.NORMAL)
        val harness = show(progress)
        campaign(1, Difficulty.NORMAL, "Пройдено").assertIsEnabled()
        campaign(1, Difficulty.HARD, "Доступно").performScrollTo().assertIsEnabled().performClick()
        campaign(1, Difficulty.EASY, "Пройдено").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(SessionConfig(SessionSlot.CAMPAIGN, Difficulty.HARD, 1),
                SessionConfig(SessionSlot.CAMPAIGN, Difficulty.EASY, 1)), harness.starts)
        }
    }

    @Test fun endlessRemainsClosedAfterNineEasyVictoriesAndCannotStart() {
        val harness = show(CampaignProgress(List(9) { 1 } + 0), SessionSlot.ENDLESS)
        compose.onNodeWithText("Режим пока закрыт").assertIsDisplayed()
        compose.onNodeWithText("Пройдено 9 из 10").assertIsDisplayed()
        compose.onNodeWithText("Начать бесконечную игру").assertDoesNotExist()
        Difficulty.entries.forEach { endless(it).assertDoesNotExist() }
        compose.runOnIdle { assertTrue(harness.starts.isEmpty()); assertTrue(harness.resumes.isEmpty()) }
    }

    @Test fun tenEasyVictoriesAllowEveryEndlessDifficultyAndPersistChoiceUntilStart() {
        val harness = show(CampaignProgress(List(10) { 1 }), SessionSlot.ENDLESS)
        endless(Difficulty.NORMAL).assertIsSelected()
        Difficulty.entries.forEach { difficulty ->
            endless(difficulty).performScrollTo().assertIsEnabled().performClick().assertIsSelected()
            Difficulty.entries.filter { it != difficulty }.forEach { endless(it).assertIsNotSelected() }
            compose.onNodeWithText("Начать бесконечную игру").performScrollTo().assertIsDisplayed().performClick()
        }
        compose.runOnIdle {
            assertEquals(Difficulty.entries.map { SessionConfig(SessionSlot.ENDLESS, it) }, harness.starts)
        }
    }

    @Test fun resumeButtonsReferOnlyToTheirOwnOngoingSession() {
        val campaignSave = campaignSession()
        val endlessSave = endlessSession()
        val harness = show(CampaignProgress(List(10) { 1 }), sessions = mapOf(SessionSlot.ENDLESS to endlessSave))
        compose.onNodeWithText("Продолжить прохождение").assertDoesNotExist()
        compose.runOnIdle { harness.state = harness.state.copy(sessions = mapOf(SessionSlot.CAMPAIGN to campaignSave)) }
        compose.onNodeWithText("Продолжить прохождение").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { harness.slot = SessionSlot.ENDLESS }
        compose.onNodeWithText("Продолжить бесконечную игру").assertDoesNotExist()
        compose.runOnIdle { harness.state = harness.state.copy(sessions = harness.state.sessions + (SessionSlot.ENDLESS to endlessSave)) }
        compose.onNodeWithText("Продолжить бесконечную игру").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(SessionSlot.CAMPAIGN, SessionSlot.ENDLESS), harness.resumes) }
    }

    @Test fun completedSessionsHaveNoResumeActionAndBusyPreventsAllNewCommands() {
        val campaignSave = campaignSession()
        val endlessSave = endlessSession()
        val harness = show(CampaignProgress(List(10) { 1 }), sessions = mapOf(
            SessionSlot.CAMPAIGN to campaignSave.copy(game = campaignSave.game.copy(phase = Phase.MATCH_END, winner = 0)),
            SessionSlot.ENDLESS to endlessSave.copy(game = endlessSave.game.copy(phase = Phase.SESSION_END)),
        ))
        compose.onNodeWithText("Продолжить прохождение").assertDoesNotExist()
        compose.runOnIdle { harness.slot = SessionSlot.ENDLESS }
        compose.onNodeWithText("Продолжить бесконечную игру").assertDoesNotExist()
        compose.runOnIdle {
            harness.state = harness.state.copy(busy = true, sessions = mapOf(SessionSlot.CAMPAIGN to campaignSave, SessionSlot.ENDLESS to endlessSave))
            harness.slot = SessionSlot.CAMPAIGN
        }
        compose.onNodeWithText("Продолжить прохождение").assertIsNotEnabled()
        campaign(1, Difficulty.EASY, "Пройдено").assertIsNotEnabled()
        compose.runOnIdle { harness.slot = SessionSlot.ENDLESS }
        compose.onNodeWithText("Продолжить бесконечную игру").assertIsNotEnabled()
        Difficulty.entries.forEach { endless(it).assertIsNotEnabled() }
        compose.onNodeWithText("Начать бесконечную игру").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(harness.starts.isEmpty()); assertTrue(harness.resumes.isEmpty()) }
    }

    private fun campaignSession(): SessionView = SessionView(SessionConfig(SessionSlot.CAMPAIGN, Difficulty.EASY, 1),
        GameEngine.view(GameEngine.newMatch("campaign-ui", 1, 100, difficulty = Difficulty.EASY)))

    private fun endlessSession(): SessionView = SessionView(SessionConfig(SessionSlot.ENDLESS, Difficulty.HARD),
        GameEngine.view(GameEngine.newMatch("endless-ui", 2, null, difficulty = Difficulty.HARD)))
}
