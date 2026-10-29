package com.example.witches

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import com.example.witches.core.Action
import com.example.witches.core.Deck
import com.example.witches.core.Difficulty
import com.example.witches.core.GameEngine
import com.example.witches.core.GameRules
import com.example.witches.core.GameState
import com.example.witches.core.Phase
import com.example.witches.core.Transition
import com.example.witches.data.AppSettings
import com.example.witches.data.PrototypeUiState
import com.example.witches.data.PrototypeRecord
import com.example.witches.data.SessionConfig
import com.example.witches.data.SessionSlot
import com.example.witches.data.SessionView
import com.example.witches.ui.ZodiacApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PrototypeUiTest {
    @get:Rule val compose = createComposeRule()

    private class Harness(initial: PrototypeUiState, initialGame: GameState?) {
        // Music is disabled here to keep card/navigation tests independent of audio focus.
        var record = PrototypeRecord(initial.settings.copy(musicEnabled = false), initial.selectedCardId, initialGame, initial.stats)
            .copy(progress = initial.progress)
        val game: GameState? get() = record.game
        var ui by mutableStateOf(initial)
        var startedTraining: Boolean? = null
        var actionCount = 0
        private var nextId = 1

        init { publish() }

        fun start(training: Boolean) {
            startSession(if (training) SessionConfig(SessionSlot.AUXILIARY, training = true)
                else SessionConfig(SessionSlot.CAMPAIGN, Difficulty.EASY, 1))
        }

        fun startSession(config: SessionConfig) {
            startedTraining = config.training
            record = record.startSession(config, GameEngine.newMatch("ui-match-${nextId++}", 7L,
                target = config.target, training = config.training, difficulty = config.difficulty))
            publish()
        }

        fun selectSession(slot: SessionSlot) {
            record = record.selectSession(slot)
            publish()
        }

        fun settings(value: AppSettings) {
            record = record.copy(settings = value)
            publish()
        }

        fun selectCard(id: Int?) {
            record = record.copy(selectedCardId = id)
            publish()
        }

        fun apply(action: Action, matchId: String, revision: Long) {
            actionCount++
            val before = requireNotNull(game)
            val result = GameEngine.apply(before, action, matchId, revision)
            check(result is Transition.Accepted) { result.toString() }
            update(result.state)
        }

        fun completeComputerTurn() {
            repeat(8) {
                val current = requireNotNull(game)
                if (current.activePlayer != 1 || current.phase in listOf(Phase.ROUND_END, Phase.MATCH_END, Phase.SESSION_END)) return
                val result = GameEngine.computerStep(current)
                check(result is Transition.Accepted) { result.toString() }
                update(result.state)
            }
            error("Учебный ход Вилл не завершён")
        }

        private fun update(next: GameState) {
            record = record.withGame(next)
            publish()
        }

        private fun publish() {
            ui = ui.copy(settings = record.settings, selectedCardId = record.selectedCardId,
                game = record.game?.let { GameEngine.view(it) }, stats = record.stats, progress = record.progress,
                activeSlot = record.activeSlot, sessionConfig = record.session?.config,
                sessions = record.sessions.mapValues { (_, saved) -> SessionView(saved.config, GameEngine.view(saved.game)) })
        }
    }

    private fun show(
        initial: PrototypeUiState = PrototypeUiState(loaded = true),
        initialGame: GameState? = null,
    ): Harness {
        val harness = Harness(initial, initialGame)
        compose.setContent {
            ZodiacApp(
                state = harness.ui,
                onSettingsChange = harness::settings,
                onSelectCard = harness::selectCard,
                onDismissMessage = { harness.ui = harness.ui.copy(message = null) },
                onNewGame = harness::start,
                onAction = harness::apply,
                onResumeComputer = {},
                onPauseComputer = {},
                onStartSession = harness::startSession,
                onSelectSession = harness::selectSession,
            )
        }
        return harness
    }

    @Test fun menuEnablesVerifiedGameAndShowsRules() {
        show()
        compose.onNodeWithText("Продолжить").assertIsNotEnabled()
        compose.onNodeWithText("Прохождение").assertIsEnabled()
        compose.onNodeWithText("Как играть").performScrollTo().performClick()
        compose.onNodeWithText("Соберите и объявите комбинации").assertIsDisplayed()
    }

    @Test fun realCatalogTypeCanBeSelectedInspectedAndDeselectedWithoutLegacySelection() {
        val harness = show(PrototypeUiState(loaded = true, selectedCardId = 4, settings = AppSettings(soundEffects = false, haptics = false)))
        compose.onNodeWithText("Посмотреть карты").performScrollTo().performClick()
        compose.onNodeWithText("Карты Зодиака").assertIsDisplayed()
        compose.onNodeWithText("36 типов · 48 карт в колоде").assertIsDisplayed()
        compose.onNodeWithText("Подробности карты").assertIsNotEnabled()
        val catalog = compose.onNodeWithTag("card_catalog_grid")
        catalog.performScrollToNode(hasContentDescription("Каталог. Водолей. Простые"))
        compose.onNodeWithContentDescription("Каталог. Водолей. Простые").assertIsNotSelected().performClick().assertIsSelected()
        catalog.performScrollToNode(hasText("Подробности карты"))
        compose.onNodeWithText("Подробности карты").performClick()
        compose.onNodeWithContentDescription("Подробности. Водолей. Простые").assertIsDisplayed()
        compose.onNodeWithText("Категория: Простые.").assertIsDisplayed()
        compose.onNodeWithText("Закрыть").performClick()
        catalog.performScrollToNode(hasText("Снять выбор"))
        compose.onNodeWithText("Снять выбор").performClick()
        catalog.performScrollToNode(hasContentDescription("Каталог. Водолей. Простые"))
        compose.onNodeWithContentDescription("Каталог. Водолей. Простые").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Каталог. Водолей. Простые").performClick().assertIsNotSelected()
        assertEquals(4, harness.ui.selectedCardId)
    }

    @Test fun settingsChangeThroughCallbacks() {
        show()
        compose.onNodeWithText("Настройки").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Звук эффектов").performScrollTo().assertIsDisplayed().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithContentDescription("Вибрация").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithContentDescription("Меньше анимаций").performScrollTo().assertIsOff().performClick().assertIsOn()
    }

    @Test fun storageErrorIsVisibleAndDismissible() {
        show(PrototypeUiState(loaded = true, message = "Файл настроек повреждён."))
        compose.onNodeWithText("Файл настроек повреждён.").assertIsDisplayed()
        compose.onNodeWithText("Понятно").performClick()
        compose.onNodeWithText("Прохождение").performScrollTo().assertIsDisplayed()
    }

    @Test fun newMatchPerformsHandAndDrawPhasesThroughUi() {
        val harness = show(PrototypeUiState(loaded = true, settings = AppSettings(false, false)))
        startFirstCampaignLevel()
        compose.onNodeWithContentDescription("Колода. Осталось 24 карт").assertIsDisplayed()
        compose.onNodeWithText("ВАША РУКА · 8").assertIsDisplayed()
        val before = requireNotNull(harness.game)
        val play = GameEngine.legalActions(before).filterIsInstance<Action.Play>().first()
        val handDescription = cardDescription(play.cardId)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(before.players[0].hand.indexOf(play.cardId)).assertIsDisplayed()
        compose.onAllNodesWithContentDescription(handDescription).onFirst().performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        clickBoardSlot(before, play.slot)
        compose.onNodeWithText("Вторая фаза: откройте карту колоды").performScrollTo().assertIsDisplayed()
        assertEquals(Phase.DRAW, harness.game?.phase)
        assertEquals(7, harness.game?.players?.get(0)?.hand?.size)
        compose.onNodeWithContentDescription("Колода. Осталось 24 карт").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("Найдите пару открытой карте или выберите пустое место").performScrollTo().assertIsDisplayed()
        val drawn = requireNotNull(harness.game)
        assertEquals(23, drawn.deck.size)
        assertEquals(Phase.DRAW_TARGET, drawn.phase)
        val resolve = GameEngine.legalActions(drawn).filterIsInstance<Action.ResolveDraw>().first()
        clickBoardSlot(drawn, resolve.slot)
        assertNull(harness.game?.drawnCard)
        assertNotEquals(Phase.DRAW_TARGET, harness.game?.phase)
        assertTrue(GameEngine.validate(requireNotNull(harness.game)))
    }

    @Test fun pauseMenuContinueAndOverwriteUseTheCurrentMatch() {
        val auxiliary = GameEngine.newMatch("preserved-legacy", 91)
        val harness = show(initialGame = auxiliary)
        startFirstCampaignLevel()
        compose.onNodeWithText("Начать новую партию?").assertDoesNotExist()
        assertEquals(auxiliary, harness.record.sessions[SessionSlot.AUXILIARY]?.game)
        assertEquals(100, harness.game?.target)
        assertEquals(Difficulty.EASY, harness.game?.difficulty)
        val firstId = harness.game?.matchId
        compose.onNodeWithText("Пауза").performClick()
        compose.onNodeWithText("Партия сохраняется после каждого принятого действия. Выход в меню сохранит её для продолжения.").assertIsDisplayed()
        compose.onNodeWithText("В меню").performClick()
        compose.onNodeWithText("Продолжить").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Колода. Осталось 24 карт").assertIsDisplayed()
        assertEquals(firstId, harness.game?.matchId)
        compose.onNodeWithText("Пауза").performClick()
        compose.onNodeWithText("В меню").performClick()
        startFirstCampaignLevel()
        compose.onNodeWithText("Начать новую партию?").assertIsDisplayed()
        assertEquals(firstId, harness.game?.matchId)
        compose.onNodeWithText("Отмена").performClick()
        assertEquals(firstId, harness.game?.matchId)
        assertEquals(auxiliary, harness.record.sessions[SessionSlot.AUXILIARY]?.game)
        compose.onNodeWithContentDescription("Уровень 1. Лёгкая. Доступно").performScrollTo().performClick()
        compose.onNodeWithText("Начать заново").performClick()
        assertNotEquals(firstId, harness.game?.matchId)
        assertEquals(auxiliary, harness.record.sessions[SessionSlot.AUXILIARY]?.game)
        compose.onNodeWithContentDescription("Колода. Осталось 24 карт").assertIsDisplayed()
    }

    @Test fun originalTutorialDeclarationShowsSeventyPointVictory() {
        val harness = show(PrototypeUiState(loaded = true, settings = AppSettings(false, false)))
        compose.onNodeWithText("Как играть").performScrollTo().performClick()
        compose.onNodeWithText("Учебная партия").performScrollTo().performClick()
        assertEquals(true, harness.startedTraining)
        assertEquals("aquarius_pentagon", Deck.card(requireNotNull(harness.game).players[0].hand.first()).type.key)
        listOf("sagittarius_fire" to "sagittarius_square", "cancer_square" to "cancer", "capricorn" to null).forEach { (key, target) ->
            playCardThroughUi(harness, key, target)
            val beforeDraw = compose.runOnIdle { requireNotNull(harness.game) }
            val context = "Карта $key, match=${beforeDraw.matchId}, revision=${beforeDraw.revision}"
            assertEquals("$context: перед нажатием колоды", Phase.DRAW, beforeDraw.phase)
            compose.onNodeWithContentDescription("Колода. Осталось ${beforeDraw.deck.size} карт")
                .assertIsDisplayed().assertIsEnabled().performClick()
            val drawn = compose.runOnIdle { requireNotNull(harness.game) }
            assertEquals("$context: после нажатия колоды, revision=${drawn.revision}", Phase.DRAW_TARGET, drawn.phase)
            assertEquals("$context: открытие одной карты", beforeDraw.revision + 1, drawn.revision)
            assertEquals("$context: уменьшение колоды", beforeDraw.deck.size - 1, drawn.deck.size)
            val targets = GameEngine.legalActions(drawn).filterIsInstance<Action.ResolveDraw>()
            assertTrue("$context: открытая карта ${drawn.drawnCard} должна иметь допустимую цель", targets.isNotEmpty())
            val resolve = targets.first()
            clickBoardSlot(drawn, resolve.slot)
            compose.runOnIdle { harness.completeComputerTurn() }
        }
        playCardThroughUi(harness, "leo", "leo_fire")
        val declaring = requireNotNull(harness.game)
        assertEquals(Phase.DECLARE, declaring.phase)
        compose.onNodeWithText("Объявить комбинации?").assertIsDisplayed()
        compose.onNodeWithText("Огонь: 70").assertIsDisplayed()
        compose.onNodeWithText("Итого: 70 очков.").assertIsDisplayed()
        compose.onNodeWithText("Продолжить розыгрыш").assertDoesNotExist()
        val deckBefore = declaring.deck
        compose.onNodeWithText("Объявить").performClick()
        compose.onNodeWithText("Победа!").assertIsDisplayed()
        assertEquals(Phase.MATCH_END, harness.game?.phase)
        assertEquals(70L, harness.game?.players?.get(0)?.score)
        assertEquals(deckBefore, harness.game?.deck)
        compose.onNodeWithText("В меню").performClick()
        compose.onNodeWithText("Продолжить").performScrollTo().assertIsNotEnabled()
    }

    @Test fun bothPublicPoolsShowTheActualCardsFromTheSourceTutorial() {
        // zodiac_tutorial_party steps 1..4: player takes Sagittarius; Will takes Pisces and Capricorn.
        val state = sourceTutorialState(humanTurns = 1)
        assertEquals(listOf("sagittarius_fire", "sagittarius_square"), state.players[0].captured.map { Deck.card(it).type.key }.sorted())
        assertEquals(listOf("capricorn", "capricorn_triangle", "pisces", "pisces_water"), state.players[1].captured.map { Deck.card(it).type.key }.sorted())
        val harness = show(initialGame = state)
        compose.onNodeWithText("Продолжить").performScrollTo().performClick()

        compose.onNodeWithText("Ваши наборы · 2").assertIsDisplayed().performClick()
        compose.onNodeWithText("Ваши собранные карты").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Ваши собранные карты. ", substring = true).assertCountEquals(2)
        state.players[0].captured.forEach { id ->
            compose.onNodeWithContentDescription(cardDescription(id, "Ваши собранные карты. ")).performScrollTo().assertIsDisplayed()
        }
        compose.onAllNodesWithContentDescription("Собранные карты Вилл. ", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Закрыть").performClick()

        compose.onNodeWithText("Наборы Вилл · 4").assertIsDisplayed().performClick()
        compose.onNodeWithText("Собранные карты Вилл").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Собранные карты Вилл. ", substring = true).assertCountEquals(4)
        state.players[1].captured.forEach { id ->
            compose.onNodeWithContentDescription(cardDescription(id, "Собранные карты Вилл. ")).performScrollTo().assertIsDisplayed()
        }
        compose.onAllNodesWithContentDescription("Ваши собранные карты. ", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Закрыть").performClick()
        assertEquals(state, harness.game)
        assertEquals(0, harness.actionCount)
    }

    @Test fun wrongTutorialCardOpensDetailsWithoutPlayingOrChangingTheStep() {
        val harness = show(PrototypeUiState(loaded = true, settings = AppSettings(false, false)))
        compose.onNodeWithText("Как играть").performScrollTo().performClick()
        compose.onNodeWithText("Учебная партия").performScrollTo().performClick()
        val before = requireNotNull(harness.game)
        val wrong = before.players[0].hand.first { Deck.card(it).type.key == "aquarius_pentagon" }
        val ordinary = before.copy(training = false, target = 900)
        val ordinaryPlay = GameEngine.legalActions(ordinary).filterIsInstance<Action.Play>().first { it.cardId == wrong }
        assertTrue(GameEngine.apply(before, ordinaryPlay) is Transition.Rejected)
        compose.onNodeWithText("Выберите Стрельца · Огонь в руке и заберите Стрельца · Квадрат. Для пары нужен одинаковый знак.").performScrollTo().assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(before.players[0].hand.indexOf(wrong)).assertIsDisplayed()
        compose.onNodeWithContentDescription(cardDescription(wrong)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Категория: Пятиугольник.").performScrollTo().assertIsDisplayed()
        assertEquals(before, harness.game)
        assertEquals(0, harness.actionCount)
        compose.onNodeWithText("Закрыть").performClick()
        compose.onNodeWithContentDescription(cardDescription(wrong)).assertIsNotSelected()
        assertEquals(1, GameEngine.tutorialStep(requireNotNull(harness.game))?.index)

        playCardThroughUi(harness, "sagittarius_fire", "sagittarius_square")
        assertEquals(1, harness.actionCount)
        assertEquals(Phase.DRAW, harness.game?.phase)
        assertEquals(2, GameEngine.tutorialStep(requireNotNull(harness.game))?.index)
        compose.onNodeWithText("Карта руки разыграна. Теперь откройте верхнюю карту центральной колоды.").performScrollTo().assertIsDisplayed()
    }

    @Test fun decliningOriginalCombinationResumesThePendingDraw() {
        val declaring = sourceTutorialState().copy(training = false, target = 900)
        val harness = show(initialGame = declaring)
        compose.onNodeWithText("Продолжить").performScrollTo().performClick()
        compose.onNodeWithText("Продолжить розыгрыш").performClick()
        compose.onNodeWithText("Вторая фаза: откройте карту колоды").performScrollTo().assertIsDisplayed()
        assertEquals(Phase.DRAW, harness.game?.phase)
        assertEquals(0L, harness.game?.players?.get(0)?.score)
        assertEquals(declaring.players[0].captured, harness.game?.players?.get(0)?.captured)
    }

    private fun playCardThroughUi(harness: Harness, key: String, targetKey: String?) {
        val current = requireNotNull(harness.game)
        val card = current.players[0].hand.first { Deck.card(it).type.key == key }
        val actions = GameEngine.legalActions(current).filterIsInstance<Action.Play>().filter { it.cardId == card }
        val action = if (targetKey == null) actions.first() else actions.first { current.board[it.slot]?.let(Deck::card)?.type?.key == targetKey }
        val handRow = compose.onNode(hasScrollToIndexAction())
        val trace = mutableListOf<String>()
        fun bounds(node: SemanticsNode?): String = if (node == null) "missing" else
            "id=${node.id}, clipped=${node.boundsInRoot}, layout=${runCatching { node.layoutInfo.coordinates.boundsInRoot() }.getOrNull()}, placed=${node.layoutInfo.isPlaced}"
        fun snapshot(stage: String) {
            val row = handRow.fetchSemanticsNode()
            val visibleCard = compose.onAllNodesWithContentDescription(cardDescription(card)).fetchSemanticsNodes().firstOrNull()
            val vertical = generateSequence(row.parent) { it.parent }
                .firstOrNull { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            trace += "$stage: card[${bounds(visibleCard)}], handRow[${bounds(row)}], verticalViewport[${bounds(vertical)}]"
        }
        try {
            snapshot("before-index")
            // Place the exact lazy item horizontally; its row stays visible in the fixed viewport.
            handRow.performScrollToIndex(current.players[0].hand.indexOf(card))
            snapshot("after-index")
            handRow.assertIsDisplayed()
            snapshot("after-visible")
            compose.onNodeWithContentDescription(cardDescription(card)).assertIsDisplayed().performClick().assertIsSelected()
        } catch (failure: AssertionError) {
            throw AssertionError("Карта $key, match=${current.matchId}, revision=${current.revision}\n${trace.joinToString("\n")}", failure)
        }
        clickBoardSlot(current, action.slot)
        val after = compose.runOnIdle { requireNotNull(harness.game) }
        assertEquals("Карта $key, match=${current.matchId}: ход должен изменить revision", current.revision + 1, after.revision)
        assertTrue("Карта $key должна уйти из руки после выбора цели", card !in after.players[0].hand)
    }

    private fun startFirstCampaignLevel() {
        compose.onNodeWithText("Прохождение").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Уровень 1. Лёгкая. Доступно")
            .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
    }

    private fun cardDescription(id: Int, prefix: String = "Рука. "): String {
        val type = Deck.card(id).type
        return "$prefix${type.sign.label}. ${type.group.label}" + (type.guardian?.let { ". ${it.label}" } ?: "")
    }

    private fun clickBoardSlot(state: GameState, slot: Int) {
        val rows = listOf(listOf(10, 0, 1, 2, 13), listOf(11, 3, 4, 5, 12), listOf(9, 6, 7, 8, 14))
        val row = rows.indexOfFirst { slot in it }
        val column = rows[row].indexOf(slot)
        val description = if (state.board[slot] == null) "Пустая клетка, ряд ${row + 1}, столбец ${column + 1}"
            else "Стол, ряд ${row + 1}, столбец ${column + 1}. "
        compose.onNode(hasContentDescription(description, substring = true)).assertIsDisplayed().performClick()
    }

    private fun sourceTutorialState(humanTurns: Int = 4): GameState {
        require(humanTurns in 1..4)
        fun accepted(state: GameState, action: Action): GameState = (GameEngine.apply(state, action) as Transition.Accepted).state
        fun play(state: GameState, key: String, targetKey: String? = null): GameState {
            val card = state.players[0].hand.first { Deck.card(it).type.key == key }
            val actions = GameEngine.legalActions(state).filterIsInstance<Action.Play>().filter { it.cardId == card }
            val action = if (targetKey == null) actions.first() else actions.first { state.board[it.slot]?.let(Deck::card)?.type?.key == targetKey }
            return accepted(state, action)
        }
        fun finishCycle(state: GameState): GameState {
            val draw = accepted(state, Action.Draw)
            var current = accepted(draw, GameEngine.legalActions(draw).first())
            repeat(8) {
                if (current.activePlayer != 1 || current.phase in listOf(Phase.ROUND_END, Phase.MATCH_END)) return current
                current = (GameEngine.computerStep(current) as Transition.Accepted).state
            }
            error("Учебный ход Вилл не завершён")
        }
        var state = GameEngine.newMatch("source-tutorial-ui", 0L, training = true)
        listOf("sagittarius_fire" to "sagittarius_square", "cancer_square" to "cancer", "capricorn" to null, "leo" to "leo_fire")
            .take(humanTurns).forEachIndexed { index, (key, target) ->
                state = play(state, key, target)
                if (index < 3) state = finishCycle(state)
            }
        if (humanTurns == 4) check(state.phase == Phase.DECLARE && GameRules.combinations(state.players[0].captured).single().points == 70)
        check(GameEngine.validate(state))
        return state
    }
}
