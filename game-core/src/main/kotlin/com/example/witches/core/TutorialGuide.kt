package com.example.witches.core

/** Source: zodiac_tutorial_party.party and zodiac_tutorial.onWidgetEvent, not hand size alone. */
internal object TutorialGuide {
    val packKeys = listOf(
        "pisces_water", "aries_fire", "cancer", "virgo_square", "libra_pentagon", "gemini", "sagittarius_square", "capricorn_triangle",
        "aquarius_pentagon", "taurus_triangle", "sagittarius_fire", "pisces_square", "cancer_square", "capricorn", "scorpio", "leo",
        "pisces", "aries_triangle", "virgo", "virgo", "gemini_air", "capricorn_earth", "aquarius", "aquarius",
        "aquarius_air", "capricorn", "aries", "aries", "leo_fire", "libra", "taurus", "sagittarius",
        "libra", "gemini", "taurus", "sagittarius", "pisces", "scorpio", "leo", "cancer",
        "gemini_pentagon", "scorpio_pentagon", "leo_pentagon", "taurus_earth", "virgo_earth", "scorpio_water", "cancer_water", "libra_air",
    )

    private data class Beat(val actor: Int, val hand: String, val target: String?, val draw: String?, val drawTarget: String?)
    private data class Pattern(
        val index: Int, val actor: Int, val phase: Phase, val hands: List<List<String>>,
        val captured: List<List<String>>, val board: List<String>, val deckCount: Int,
        val drawn: String?, val expectedHand: String? = null, val expectedTarget: String? = null,
    ) {
        fun matches(view: GameView): Boolean = actor == view.actor && phase == view.phase &&
            hands[view.viewer].sorted() == keys(view.hand) && hands[1 - view.viewer].size == view.opponentHandCount &&
            captured[view.viewer].sorted() == keys(view.ownCaptured) && captured[1 - view.viewer].sorted() == keys(view.opponentCaptured) &&
            board.sorted() == keys(view.board.filterNotNull()) && deckCount == view.deckCount &&
            drawn == view.drawnCard?.let { Deck.card(it).type.key }
    }

    // Fixed prefix of the source tutorial. Empty placements are intentionally compared by cards, not cells.
    private val patterns: List<Pattern> = buildList {
        val hands = listOf(packKeys.subList(8, 16).toMutableList(), packKeys.subList(16, 24).toMutableList())
        val captured = List(2) { mutableListOf<String>() }
        val board = packKeys.take(8).toMutableList()
        var deckCount = 24
        fun save(index: Int, actor: Int, phase: Phase, drawn: String? = null, hand: String? = null, target: String? = null) {
            add(Pattern(index, actor, phase, hands.map { it.toList() }, captured.map { it.toList() },
                board.toList(), deckCount, drawn, hand, target))
        }
        fun resolve(actor: Int, card: String, target: String?) {
            if (target == null) board.add(card) else {
                check(board.remove(target))
                captured[actor].addAll(listOf(card, target))
            }
        }
        val beats = listOf(
            Beat(0, "sagittarius_fire", "sagittarius_square", "aquarius_air", null),
            Beat(1, "pisces", "pisces_water", "capricorn", "capricorn_triangle"),
            Beat(0, "cancer_square", "cancer", "aries", "aries_fire"),
            Beat(1, "virgo", "virgo_square", "aries", null),
            Beat(0, "capricorn", null, "leo_fire", null),
            Beat(1, "aries_triangle", "aries", "libra", "libra_pentagon"),
            Beat(0, "leo", "leo_fire", null, null),
        )
        beats.forEachIndexed { turn, beat ->
            val index = turn * 2 + 1
            save(index, beat.actor, Phase.HAND, hand = beat.hand, target = beat.target)
            check(hands[beat.actor].remove(beat.hand))
            resolve(beat.actor, beat.hand, beat.target)
            if (beat.draw == null) {
                save(14, beat.actor, Phase.DECLARE)
            } else {
                save(index + 1, beat.actor, Phase.DRAW)
                deckCount--
                save(index + 1, beat.actor, Phase.DRAW_TARGET, drawn = beat.draw)
                resolve(beat.actor, beat.draw, beat.drawTarget)
            }
        }
    }

    fun step(view: GameView): TutorialStep? {
        if (!view.training) return null
        if (view.phase == Phase.MATCH_END) return TutorialStep(14, view.actor, "Обучение завершено. Объявленные комбинации принесли очки.")
        val pattern = patterns.firstOrNull { view.round == 1 && it.matches(view) }
            ?: return TutorialStep(0, view.actor,
                "Эта учебная партия отклонилась от исходного сценария в прежней версии. Начните обучение заново.", restartRequired = true)
        val cardId = when {
            pattern.actor == 0 && view.viewer == 0 && pattern.phase == Phase.HAND -> view.hand.firstOrNull { Deck.card(it).type.key == pattern.expectedHand }
            pattern.phase == Phase.DRAW_TARGET -> view.drawnCard
            else -> null
        }
        val targetId = if (pattern.actor == 0 && pattern.phase == Phase.HAND) {
            view.board.filterNotNull().firstOrNull { Deck.card(it).type.key == pattern.expectedTarget }
        } else if (pattern.actor == 0 && pattern.phase == Phase.DRAW_TARGET) {
            view.board.filterNotNull().firstOrNull { Deck.card(it).type.sign == cardId?.let(Deck::card)?.type?.sign }
        } else null
        return TutorialStep(pattern.index, pattern.actor, instruction(pattern), cardId, targetId)
    }

    fun filter(view: GameView, actions: List<Action>): List<Action> {
        val step = step(view) ?: return actions
        if (step.restartRequired) return emptyList()
        if (view.phase == Phase.DECLARE) return actions.filter { it == Action.Announce }
        if (view.actor != 0 || view.phase != Phase.HAND) return actions
        return actions.filter { action -> action is Action.Play && action.cardId == step.expectedCardId &&
            (step.expectedTargetId == null || view.board.getOrNull(action.slot) == step.expectedTargetId) }
    }

    private fun instruction(pattern: Pattern): String = when {
        pattern.index == 14 -> "Собраны три карты Огня: 70 очков. Объявите комбинацию, чтобы завершить обучение и получить очки."
        pattern.actor == 1 && pattern.phase == Phase.HAND -> "Теперь ходит Вилл. Следите, как она разыгрывает карту руки и пополняет свой пул."
        pattern.actor == 1 -> "Вилл разыгрывает открытую карту колоды. Захваченные карты видны в её пуле."
        pattern.phase == Phase.DRAW -> "Карта руки разыграна. Теперь откройте верхнюю карту центральной колоды."
        pattern.index == 1 -> "Выберите Стрельца · Огонь в руке и заберите Стрельца · Квадрат. Для пары нужен одинаковый знак."
        pattern.index == 5 -> "Выберите Рака · Квадрат и заберите простого Рака. Категории карт могут различаться: совпадать должен знак."
        pattern.index == 9 -> "Выберите простого Козерога. Пары на столе нет: положите карту на любое подсвеченное пустое место."
        pattern.index == 13 -> "Выберите простого Льва и заберите Льва · Огонь. В вашем пуле соберутся три карты Огня."
        pattern.index == 6 -> "Открыт Овен. Заберите Овна со стола: обе карты попадут в ваш пул."
        pattern.index == 2 -> "У открытого Водолея нет пары. Положите карту на любое подсвеченное пустое место."
        pattern.index == 10 -> "У открытого Льва · Огонь пока нет пары. Положите его на любое подсвеченное пустое место."
        else -> "Разыграйте открытую карту колоды по обычным правилам захвата."
    }

    private fun keys(ids: List<Int>): List<String> = ids.map { Deck.card(it).type.key }.sorted()
}
