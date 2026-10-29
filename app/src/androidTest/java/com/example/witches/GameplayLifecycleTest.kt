package com.example.witches

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import org.junit.Rule
import org.junit.Test

/** Real Activity → ViewModel → DataStore → computer coroutine; no injected game state. */
class GameplayLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun tutorialComputerTurnResumesAfterPauseAndIsNotRepeatedAfterRecreation() {
        waitFor(hasText("Как играть"))
        compose.onNodeWithText("Как играть").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Учебная партия").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            exists(hasText("Начать заново")) || exists(hasText(FIRST_INSTRUCTION))
        }
        if (exists(hasText("Начать заново"))) compose.onNodeWithText("Начать заново").performClick()
        waitFor(hasText(FIRST_INSTRUCTION))
        waitForPresentation()

        val handRow = compose.onNode(hasScrollToIndexAction())
        handRow.performScrollToIndex(2)
        handRow.assertIsDisplayed()
        compose.onNodeWithContentDescription("Рука. Стрелец. Огонь")
            .assertIsDisplayed().performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Стол, ряд 3, столбец 3. Стрелец. Квадрат")
            .assertIsDisplayed().performClick()
        waitFor(hasText("Карта руки разыграна. Теперь откройте верхнюю карту центральной колоды."))
        waitForPresentation()
        compose.onNodeWithContentDescription("Колода. Осталось 24 карт")
            .assertIsDisplayed().assertIsEnabled().performClick()
        waitFor(hasText("У открытого Водолея нет пары. Положите карту на любое подсвеченное пустое место."))
        waitForPresentation()
        compose.onNodeWithContentDescription("Открытая карта. Водолей. Воздух").assertIsDisplayed()

        val emptyCell = compose.onNodeWithContentDescription("Пустая клетка, ряд 1, столбец 1")
        emptyCell.assertIsDisplayed().assertIsEnabled()
        // Hold only presentation frames while issuing Pause; disk writes and the real VM still run.
        compose.mainClock.autoAdvance = false
        try {
            emptyCell.performClick()
            compose.onNodeWithText("Пауза").performClick()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        waitFor(hasText("Продолжить партию"))
        waitFor(hasText(COMPUTER_INSTRUCTION))
        compose.onNodeWithText("Наборы Вилл · 0").assertExists()
        compose.onNodeWithText("Ваши наборы · 2").assertExists()

        compose.onNodeWithText("Продолжить партию").performClick()
        waitFor(hasText(SECOND_INSTRUCTION))
        waitForPresentation()
        assertFirstCyclePools()

        compose.activityRule.scenario.recreate()
        waitFor(hasText(SECOND_INSTRUCTION))
        waitForPresentation()
        assertFirstCyclePools()
        compose.onNodeWithText("ВАША РУКА · 7").assertExists()

        compose.onNodeWithText("Пауза").performClick()
        waitFor(hasText("Продолжить партию"))
        compose.onNodeWithText("В меню").performClick()
        waitFor(hasText("Прохождение"))
    }

    private fun assertFirstCyclePools() {
        compose.onNodeWithText("Наборы Вилл · 4").assertIsDisplayed()
        compose.onNodeWithText("Ваши наборы · 2").assertIsDisplayed()
    }

    private fun waitForPresentation() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("move-card-0").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(15_000) { exists(matcher) }
    }

    private fun exists(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val FIRST_INSTRUCTION = "Выберите Стрельца · Огонь в руке и заберите Стрельца · Квадрат. Для пары нужен одинаковый знак."
        const val COMPUTER_INSTRUCTION = "Теперь ходит Вилл. Следите, как она разыгрывает карту руки и пополняет свой пул."
        const val SECOND_INSTRUCTION = "Выберите Рака · Квадрат и заберите простого Рака. Категории карт могут различаться: совпадать должен знак."
    }
}
