package com.example.witches

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test

class RecreationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun catalogSelectionAndPageSurviveActivityRecreation() {
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Посмотреть карты")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Посмотреть карты").performScrollTo().performClick()
        compose.onNodeWithText("Карты Зодиака").assertIsDisplayed()
        compose.onNodeWithTag("card_catalog_grid").performScrollToNode(hasContentDescription("Каталог. Водолей. Простые"))
        compose.onNodeWithContentDescription("Каталог. Водолей. Простые").assertIsNotSelected().performClick().assertIsSelected()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Карты Зодиака").assertIsDisplayed()
        compose.onNodeWithTag("card_catalog_grid").performScrollToNode(hasContentDescription("Каталог. Водолей. Простые"))
        compose.onNodeWithContentDescription("Каталог. Водолей. Простые").assertIsSelected()
    }
}

