package com.example.witches.core

enum class Sign(val label: String) {
    AQUARIUS("Водолей"), CAPRICORN("Козерог"), LIBRA("Весы"), GEMINI("Близнецы"),
    TAURUS("Телец"), SAGITTARIUS("Стрелец"), ARIES("Овен"), SCORPIO("Скорпион"),
    VIRGO("Дева"), LEO("Лев"), PISCES("Рыбы"), CANCER("Рак")
}

enum class Group(val label: String) {
    SIMPLE("Простые"), TRIANGLE("Треугольник"), SQUARE("Квадрат"), PENTAGON("Пятиугольник"),
    FIRE("Огонь"), EARTH("Земля"), WATER("Вода"), AIR("Воздух")
}

enum class Guardian(val label: String) {
    WILL("Вилл"), CORNELIA("Корнелия"), IRMA("Ирма"), TARANEE("Тарани"), HAY_LIN("Хай Лин")
}

data class CardType(val key: String, val sign: Sign, val group: Group, val guardian: Guardian? = null)
data class Card(val id: Int, val type: CardType)

enum class Difficulty(val label: String) { EASY("Лёгкая"), NORMAL("Средняя"), HARD("Сложная") }

data class PlayerState(val hand: List<Int>, val captured: List<Int>, val score: Long)
enum class Phase { HAND, DRAW, DRAW_TARGET, DECLARE, ROUND_END, MATCH_END, SESSION_END }
enum class Resume { DRAW, END_TURN }

data class GameState(
    val matchId: String,
    val revision: Long,
    val round: Int,
    val target: Int?,
    val players: List<PlayerState>,
    val board: List<Int?>,
    val deck: List<Int>,
    val activePlayer: Int,
    val phase: Phase,
    val drawnCard: Int?,
    val resume: Resume?,
    val rngState: Long,
    val aiDenominator: Int = 3,
    val lastAward: Int = 0,
    val roundWinner: Int? = null,
    val winner: Int? = null,
    val training: Boolean = false,
    val difficulty: Difficulty = Difficulty.NORMAL,
)

data class GameView(
    val matchId: String,
    val revision: Long,
    val round: Int,
    val target: Int?,
    val actor: Int,
    val phase: Phase,
    val hand: List<Int>,
    val opponentHandCount: Int,
    val board: List<Int?>,
    val deckCount: Int,
    val drawnCard: Int?,
    val ownCaptured: List<Int>,
    val opponentCaptured: List<Int>,
    val scores: List<Long>,
    val lastAward: Int,
    val roundWinner: Int?,
    val winner: Int?,
    val training: Boolean,
    val aiDenominator: Int,
    val viewer: Int = 0,
    val difficulty: Difficulty = Difficulty.NORMAL,
)

sealed interface Action {
    data class Play(val cardId: Int, val slot: Int) : Action
    data object Draw : Action
    data class ResolveDraw(val slot: Int) : Action
    data object Announce : Action
    data object Continue : Action
    data object NextRound : Action
    data object EndSession : Action
}

sealed interface Transition {
    data class Accepted(val state: GameState) : Transition
    data class Rejected(val reason: String) : Transition
}

data class Combination(val key: String, val name: String, val points: Int)

data class TutorialStep(
    val index: Int,
    val actor: Int,
    val instruction: String,
    val expectedCardId: Int? = null,
    val expectedTargetId: Int? = null,
    val restartRequired: Boolean = false,
)
