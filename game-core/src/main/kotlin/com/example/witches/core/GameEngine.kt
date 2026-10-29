package com.example.witches.core

object GameEngine {
    private val initialSlots = listOf(0, 1, 2, 3, 5, 6, 7, 8)

    fun newMatch(matchId: String, seed: Long, target: Int? = 900, training: Boolean = false, difficulty: Difficulty = Difficulty.NORMAL): GameState {
        require(matchId.isNotBlank() && matchId.length <= 128) { "Некорректный ID партии" }
        require(target == null || target in 1..1_000_000) { "Некорректная цель партии" }
        return deal(GameState(
            matchId = matchId, revision = 0, round = 1, target = if (training) 1 else target,
            players = List(2) { PlayerState(emptyList(), emptyList(), 0) },
            board = List(15) { null }, deck = emptyList(), activePlayer = 0,
            phase = Phase.HAND, drawnCard = null, resume = null, rngState = seed, training = training,
            difficulty = if (training) Difficulty.NORMAL else difficulty,
        ))
    }

    fun view(state: GameState, viewer: Int = 0): GameView {
        require(viewer in 0..1)
        return GameView(
            matchId = state.matchId, revision = state.revision, round = state.round, target = state.target,
            actor = state.activePlayer, phase = state.phase, hand = state.players[viewer].hand.toList(),
            opponentHandCount = state.players[1 - viewer].hand.size, board = state.board.toList(),
            deckCount = state.deck.size, drawnCard = state.drawnCard,
            ownCaptured = state.players[viewer].captured.toList(), opponentCaptured = state.players[1 - viewer].captured.toList(),
            scores = state.players.map { it.score }, lastAward = state.lastAward,
            roundWinner = state.roundWinner, winner = state.winner, training = state.training,
            aiDenominator = state.aiDenominator, viewer = viewer, difficulty = state.difficulty,
        )
    }

    fun legalActions(state: GameState): List<Action> =
        if (validate(state)) legalActions(view(state, state.activePlayer)) else emptyList()

    fun tutorialStep(state: GameState): TutorialStep? = tutorialStep(view(state, 0))

    fun tutorialStep(view: GameView): TutorialStep? = TutorialGuide.step(view)

    fun legalActions(view: GameView): List<Action> {
        if (view.phase == Phase.MATCH_END || view.phase == Phase.SESSION_END) return emptyList()
        val endSession = if (view.target == null && !view.training) listOf(Action.EndSession) else emptyList()
        if (view.actor != view.viewer && view.phase != Phase.ROUND_END) return endSession
        val actions = when (view.phase) {
            Phase.HAND -> view.hand.flatMap { card -> targets(view.board, card).map { Action.Play(card, it) } }
            Phase.DRAW -> if (view.deckCount > 0) listOf(Action.Draw) else emptyList()
            Phase.DRAW_TARGET -> view.drawnCard?.let { card -> targets(view.board, card).map { Action.ResolveDraw(it) } }.orEmpty()
            Phase.DECLARE -> listOf(Action.Announce, Action.Continue)
            Phase.ROUND_END -> listOf(Action.NextRound)
            Phase.MATCH_END, Phase.SESSION_END -> emptyList()
        }
        return TutorialGuide.filter(view, actions) + endSession
    }

    fun apply(
        state: GameState,
        action: Action,
        expectedMatchId: String = state.matchId,
        expectedRevision: Long = state.revision,
    ): Transition {
        if (expectedMatchId != state.matchId || expectedRevision != state.revision) return Transition.Rejected("Ход устарел")
        if (!validate(state)) return Transition.Rejected("Поврежденное состояние партии")
        if (state.phase == Phase.MATCH_END || state.phase == Phase.SESSION_END) return Transition.Rejected("Партия уже завершена")
        tutorialStep(state)?.takeIf { it.restartRequired }?.let { return Transition.Rejected(it.instruction) }
        if (action !in legalActions(state)) return Transition.Rejected("Недопустимый ход")
        if (action == Action.Announce && GameRules.combinations(state.players[state.activePlayer].captured).sumOf { it.points } >
            Long.MAX_VALUE - state.players[state.activePlayer].score) return Transition.Rejected("Достигнут предел счётчика очков")
        val changed = when (action) {
            is Action.Play -> play(state, action.cardId, action.slot, fromDeck = false)
            Action.Draw -> state.copy(deck = state.deck.dropLast(1), drawnCard = state.deck.last(), phase = Phase.DRAW_TARGET)
            is Action.ResolveDraw -> play(state, requireNotNull(state.drawnCard), action.slot, fromDeck = true)
            Action.Announce -> announce(state)
            Action.Continue -> resume(state.copy(resume = null), requireNotNull(state.resume))
            Action.NextRound -> deal(state.copy(round = state.round + 1))
            Action.EndSession -> state.copy(phase = Phase.SESSION_END, resume = null, lastAward = 0, roundWinner = null, winner = null)
        }.copy(revision = state.revision + 1)
        check(validate(changed)) { "Нарушен инвариант игрового перехода" }
        return Transition.Accepted(changed)
    }

    fun computerStep(state: GameState): Transition {
        if (!validate(state)) return Transition.Rejected("Поврежденное состояние партии")
        if (state.activePlayer != 1) return Transition.Rejected("Сейчас ход игрока")
        val publicView = view(state, 1)
        val legal = legalActions(publicView).filterNot { it == Action.EndSession }
        val random = when {
            state.difficulty == Difficulty.NORMAL && state.phase == Phase.DECLARE -> random(state.rngState, state.aiDenominator)
            state.difficulty == Difficulty.EASY && state.phase in listOf(Phase.HAND, Phase.DRAW_TARGET) && legal.size > 1 -> random(state.rngState, legal.size)
            else -> null
        }
        val action = ComputerOpponent.choose(publicView, legal, random?.second ?: 0)
            ?: return Transition.Rejected("Нет хода соперника")
        val result = apply(state, action)
        return when (result) {
            is Transition.Accepted -> {
                val denominator = if (state.phase == Phase.DECLARE && action == Action.Continue) maxOf(1, state.aiDenominator - 1) else result.state.aiDenominator
                Transition.Accepted(result.state.copy(rngState = random?.first ?: result.state.rngState, aiDenominator = denominator))
            }
            is Transition.Rejected -> result
        }
    }

    fun validate(state: GameState): Boolean {
        if (state.matchId.isBlank() || state.matchId.length > 128 || state.revision !in 0 until Long.MAX_VALUE || state.round !in 1 until Int.MAX_VALUE) return false
        if (state.target != null && state.target !in 1..1_000_000 || state.activePlayer !in 0..1 || state.players.size != 2) return false
        if (state.training && (state.target != 1 || state.difficulty != Difficulty.NORMAL)) return false
        if (state.board.size !in 15..50 || state.board.size % 5 != 0 || state.board[4] != null || state.deck.size > 24 || state.aiDenominator !in 1..3) return false
        if (state.players.any { it.hand.size > 8 || it.captured.size % 2 != 0 || it.score < 0 }) return false
        val all = state.deck + state.board.filterNotNull() + state.players.flatMap { it.hand + it.captured } + listOfNotNull(state.drawnCard)
        if (all.size != 48 || all.toSet() != (1..48).toSet()) return false
        if (state.lastAward < 0 || state.roundWinner?.let { it !in 0..1 } == true || state.winner?.let { it !in 0..1 } == true) return false
        if (state.phase != Phase.MATCH_END && state.target != null && state.players.any { it.score >= state.target }) return false
        if (state.phase != Phase.ROUND_END && state.phase != Phase.MATCH_END && (state.lastAward != 0 || state.roundWinner != null)) return false
        if (state.phase != Phase.MATCH_END && state.winner != null) return false
        val humanHand = state.players[0].hand.size
        val computerHand = state.players[1].hand.size
        val beforeHandPlay = if (state.activePlayer == 0) humanHand == computerHand else humanHand + 1 == computerHand
        val afterHandPlay = if (state.activePlayer == 0) humanHand + 1 == computerHand else humanHand == computerHand
        return when (state.phase) {
            Phase.HAND -> beforeHandPlay && state.drawnCard == null && state.resume == null && state.players[state.activePlayer].hand.isNotEmpty()
            Phase.DRAW -> afterHandPlay && state.drawnCard == null && state.resume == null && state.deck.isNotEmpty()
            Phase.DRAW_TARGET -> afterHandPlay && state.drawnCard != null && state.resume == null
            Phase.DECLARE -> afterHandPlay && state.drawnCard == null && state.resume != null && GameRules.combinations(state.players[state.activePlayer].captured).isNotEmpty()
            Phase.ROUND_END -> state.drawnCard == null && state.resume == null &&
                (state.roundWinner == state.activePlayer && state.lastAward > 0 && afterHandPlay ||
                    state.roundWinner == null && state.lastAward == 0 && state.activePlayer == 0 && humanHand == 0 && computerHand == 0)
            Phase.MATCH_END -> state.drawnCard == null && state.resume == null && state.winner == state.activePlayer &&
                state.roundWinner == state.winner && state.lastAward > 0 && afterHandPlay && state.target != null && state.players[state.activePlayer].score >= state.target
            Phase.SESSION_END -> state.target == null && !state.training && state.resume == null &&
                humanHand in computerHand - 1..computerHand
        }
    }

    private fun targets(board: List<Int?>, cardId: Int): List<Int> {
        val sign = Deck.card(cardId).type.sign
        val matching = board.indices.filter { it != 4 && board[it]?.let { id -> Deck.card(id).type.sign == sign } == true }
        if (matching.isNotEmpty()) return matching
        val free = board.indices.filter { it != 4 && board[it] == null }
        if (free.isNotEmpty()) return free
        // The PC implementation asserts on a full table; the adaptation adds one row only in that case.
        return if (board.size < 50) (board.size until board.size + 5).toList() else emptyList()
    }

    private fun play(state: GameState, cardId: Int, slot: Int, fromDeck: Boolean): GameState {
        val actor = state.activePlayer
        val player = state.players[actor]
        val target = state.board.getOrNull(slot)
        val board = state.board.toMutableList().apply {
            if (slot >= size) repeat(5) { add(null) }
            set(slot, if (target == null) cardId else null)
        }.toList()
        val changedPlayer = player.copy(
            hand = if (fromDeck) player.hand else player.hand.filter { it != cardId },
            captured = if (target == null) player.captured else player.captured + listOf(cardId, target),
        )
        val changed = state.copy(
            players = state.players.mapIndexed { index, old -> if (index == actor) changedPlayer else old },
            board = board, drawnCard = null,
        )
        val next = if (fromDeck) Resume.END_TURN else Resume.DRAW
        val oldPoints = GameRules.combinations(player.captured).sumOf { it.points }
        val newPoints = GameRules.combinations(changedPlayer.captured).sumOf { it.points }
        return if (newPoints > oldPoints) changed.copy(phase = Phase.DECLARE, resume = next) else resume(changed, next)
    }

    private fun resume(state: GameState, resume: Resume): GameState = when (resume) {
        Resume.DRAW -> if (state.deck.isNotEmpty()) state.copy(phase = Phase.DRAW, resume = null) else finishTurn(state)
        Resume.END_TURN -> finishTurn(state)
    }

    private fun finishTurn(state: GameState): GameState {
        if (state.players.all { it.hand.isEmpty() }) return state.copy(phase = Phase.ROUND_END, resume = null, activePlayer = 0)
        return state.copy(activePlayer = 1 - state.activePlayer, phase = Phase.HAND, resume = null)
    }

    private fun announce(state: GameState): GameState {
        val actor = state.activePlayer
        val award = GameRules.combinations(state.players[actor].captured).sumOf { it.points }
        val players = state.players.mapIndexed { index, player -> if (index == actor) player.copy(score = player.score + award) else player }
        val winner = actor.takeIf { state.target != null && players[actor].score >= state.target }
        return state.copy(players = players, phase = if (winner == null) Phase.ROUND_END else Phase.MATCH_END,
            resume = null, lastAward = award, roundWinner = actor, winner = winner)
    }

    private fun deal(state: GameState): GameState {
        var rng = state.rngState
        val pack = if (state.training) tutorialPack().toMutableList() else Deck.cards.map { it.id }.toMutableList().also { cards ->
            for (index in cards.lastIndex downTo 1) {
                val (next, selected) = random(rng, index + 1)
                rng = next
                val previous = cards[index]
                cards[index] = cards[selected]
                cards[selected] = previous
            }
        }
        val board = MutableList<Int?>(15) { null }
        initialSlots.forEach { board[it] = pack.removeAt(pack.lastIndex) }
        val players = state.players.map { player ->
            PlayerState(List(8) { pack.removeAt(pack.lastIndex) }, emptyList(), player.score)
        }
        return state.copy(players = players, board = board.toList(), deck = pack.toList(), activePlayer = 0,
            phase = Phase.HAND, drawnCard = null, resume = null, rngState = rng, aiDenominator = 3,
            lastAward = 0, roundWinner = null, winner = null)
    }

    private fun random(seed: Long, bound: Int): Pair<Long, Int> {
        val next = seed - 7046029254386353131L
        var value = next
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        value = value xor (value ushr 31)
        return next to ((value ushr 1) % bound).toInt()
    }

    private fun tutorialPack(): List<Int> {
        val available = Deck.cards.toMutableList()
        return TutorialGuide.packKeys.map { key ->
            val index = available.indexOfFirst { it.type.key == key }
            available.removeAt(index).id
        }.reversed()
    }

    private object ComputerOpponent {
        fun choose(view: GameView, legal: List<Action>, random: Int): Action? {
            return when (view.phase) {
                Phase.HAND -> when (view.difficulty) {
                    Difficulty.EASY -> legal.getOrNull(random)
                    Difficulty.NORMAL -> legal.filterIsInstance<Action.Play>().firstOrNull { view.board.getOrNull(it.slot) != null } ?: legal.firstOrNull()
                    Difficulty.HARD -> bestCapture(view, legal)
                }
                Phase.DRAW -> legal.firstOrNull()
                Phase.DRAW_TARGET -> when (view.difficulty) {
                    Difficulty.EASY -> legal.getOrNull(random)
                    Difficulty.NORMAL -> legal.firstOrNull()
                    Difficulty.HARD -> bestCapture(view, legal)
                }
                Phase.DECLARE -> if (view.difficulty != Difficulty.NORMAL || random == 0 || view.hand.size <= 2) Action.Announce else Action.Continue
                Phase.ROUND_END -> Action.NextRound
                Phase.MATCH_END, Phase.SESSION_END -> null
            }
        }

        private fun bestCapture(view: GameView, legal: List<Action>): Action? {
            fun pair(action: Action): List<Int> {
                val (card, slot) = when (action) {
                    is Action.Play -> action.cardId to action.slot
                    is Action.ResolveDraw -> requireNotNull(view.drawnCard) to action.slot
                    Action.Draw, Action.Announce, Action.Continue, Action.NextRound, Action.EndSession -> return emptyList()
                }
                return view.board.getOrNull(slot)?.let { listOf(card, it) }.orEmpty()
            }
            val captures = legal.filter { pair(it).isNotEmpty() }
            val currentPoints = GameRules.combinations(view.ownCaptured).sumOf { it.points }
            return captures.maxWithOrNull(compareBy<Action>(
                { GameRules.combinations(view.ownCaptured + pair(it)).sumOf { combination -> combination.points } - currentPoints },
                { pair(it).count { id -> Deck.card(id).type.guardian != null } },
                { pair(it).count { id -> Deck.card(id).type.group != Group.SIMPLE } },
            )) ?: legal.firstOrNull()
        }
    }
}
