package com.example.witches.core

import org.junit.Assert.*
import org.junit.Test

class GameEngineTest {
    private fun accepted(state: GameState, action: Action): GameState {
        val transition = GameEngine.apply(state, action)
        assertTrue("$action: $transition", transition is Transition.Accepted)
        return (transition as Transition.Accepted).state.also { assertTrue(GameEngine.validate(it)) }
    }

    private fun playByKey(state: GameState, key: String, boardKey: String? = null): GameState {
        val card = state.players[state.activePlayer].hand.first { Deck.card(it).type.key == key }
        val actions = GameEngine.legalActions(state).filterIsInstance<Action.Play>().filter { it.cardId == card }
        val action = if (boardKey == null) actions.first() else actions.first { state.board[it.slot]?.let(Deck::card)?.type?.key == boardKey }
        return accepted(state, action)
    }

    private fun drawAndResolve(state: GameState): GameState {
        val drawn = accepted(state, Action.Draw)
        return accepted(drawn, GameEngine.legalActions(drawn).first())
    }

    private fun completeComputerTurn(state: GameState): GameState {
        var current = state
        repeat(8) {
            if (current.activePlayer != 1 || current.phase == Phase.ROUND_END || current.phase == Phase.MATCH_END) return current
            val result = GameEngine.computerStep(current)
            assertTrue(result.toString(), result is Transition.Accepted)
            current = (result as Transition.Accepted).state
            assertTrue(GameEngine.validate(current))
        }
        fail("Соперник не завершил ход")
        return current
    }

    @Test fun tutorialSourceGivesExactDealAndFirstCapture() {
        val state = GameEngine.newMatch("tutorial", 97, training = true)
        assertEquals(listOf("pisces_water", "aries_fire", "cancer", "virgo_square", null, "libra_pentagon", "gemini", "sagittarius_square", "capricorn_triangle", null, null, null, null, null, null),
            state.board.map { it?.let(Deck::card)?.type?.key })
        assertEquals(listOf("aquarius_pentagon", "taurus_triangle", "sagittarius_fire", "pisces_square", "cancer_square", "capricorn", "scorpio", "leo"),
            state.players[0].hand.map { Deck.card(it).type.key })
        assertEquals(listOf("pisces", "aries_triangle", "virgo", "virgo", "gemini_air", "capricorn_earth", "aquarius", "aquarius"),
            state.players[1].hand.map { Deck.card(it).type.key })
        assertEquals(24, state.deck.size)
        assertEquals("aquarius_air", Deck.card(state.deck.last()).type.key)
        assertEquals(0, state.activePlayer)
        val played = playByKey(state, "sagittarius_fire", "sagittarius_square")
        assertEquals(listOf("sagittarius_fire", "sagittarius_square"), played.players[0].captured.map { Deck.card(it).type.key })
        assertNull(played.board[7])
        assertEquals(Phase.DRAW, played.phase)
        assertEquals(24, played.deck.size)
        val draw = accepted(played, Action.Draw)
        assertEquals(23, draw.deck.size)
        assertEquals("aquarius_air", Deck.card(requireNotNull(draw.drawnCard)).type.key)
        assertEquals(Phase.DRAW_TARGET, draw.phase)
        assertEquals(0, draw.activePlayer)
    }

    @Test fun completeOriginalTutorialDeclaresFireBeforeDrawingAndWinsOnce() {
        var state = GameEngine.newMatch("tutorial", 0, training = true)
        state = completeComputerTurn(drawAndResolve(playByKey(state, "sagittarius_fire", "sagittarius_square")))
        state = completeComputerTurn(drawAndResolve(playByKey(state, "cancer_square", "cancer")))
        state = completeComputerTurn(drawAndResolve(playByKey(state, "capricorn")))
        val remainingDeck = state.deck
        state = playByKey(state, "leo", "leo_fire")
        assertEquals(Phase.DECLARE, state.phase)
        assertEquals(Resume.DRAW, state.resume)
        assertEquals(remainingDeck, state.deck)
        assertEquals(listOf(Combination("fire_3", "Огонь", 70)), GameRules.combinations(state.players[0].captured))
        val ended = accepted(state, Action.Announce)
        assertEquals(Phase.MATCH_END, ended.phase)
        assertEquals(70L, ended.players[0].score)
        assertEquals(0, ended.winner)
        assertEquals(70, ended.lastAward)
        assertEquals(remainingDeck, ended.deck)
        assertTrue(GameEngine.apply(ended, Action.Announce) is Transition.Rejected)
    }

    @Test fun decliningHandCombinationResumesDrawWithoutRepeatedOffer() {
        var state = GameEngine.newMatch("tutorial", 1, training = true).copy(training = false, target = 900)
        state = completeComputerTurn(drawAndResolve(playByKey(state, "sagittarius_fire", "sagittarius_square")))
        state = completeComputerTurn(drawAndResolve(playByKey(state, "cancer_square", "cancer")))
        state = completeComputerTurn(drawAndResolve(playByKey(state, "capricorn")))
        state = playByKey(state, "leo", "leo_fire")
        val captured = state.players[0].captured
        state = accepted(state, Action.Continue)
        assertEquals(Phase.DRAW, state.phase)
        assertEquals(0L, state.players[0].score)
        state = drawAndResolve(state)
        assertEquals(1, state.activePlayer)
        assertEquals(captured, state.players[0].captured)
        assertNotEquals(Phase.DECLARE, state.phase)
    }

    @Test fun matchingOneOfSeveralTargetsIsCompulsoryAndCapturesOnlyOne() {
        val state = fixture(hand0 = listOf(37), boardCards = mapOf(0 to 6, 1 to 18))
        val actions = GameEngine.legalActions(state).filterIsInstance<Action.Play>().filter { it.cardId == 37 }
        assertEquals(listOf(Action.Play(37, 0), Action.Play(37, 1)), actions)
        assertTrue(GameEngine.apply(state, Action.Play(37, 2)) is Transition.Rejected)
        val after = accepted(state, Action.Play(37, 1))
        assertEquals(6, after.board[0])
        assertNull(after.board[1])
        assertEquals(listOf(37, 18), after.players[0].captured)
    }

    @Test fun declarationAfterDrawContinuesWithOtherActorAndScoresAllCategories() {
        val state = fixture(hand0 = listOf(3), captured0 = listOf(37, 38), boardCards = mapOf(0 to 10), drawn = 39, phase = Phase.DRAW_TARGET)
        val captured = accepted(state, Action.ResolveDraw(0))
        assertEquals(Phase.DECLARE, captured.phase)
        assertEquals(Resume.END_TURN, captured.resume)
        val declined = accepted(captured, Action.Continue)
        assertEquals(1, declined.activePlayer)
        assertEquals(Phase.HAND, declined.phase)
        val announced = accepted(captured, Action.Announce)
        assertEquals(Phase.ROUND_END, announced.phase)
        assertEquals(70L, announced.players[0].score)
        assertEquals(0L, announced.players[1].score)
        val next = accepted(announced, Action.NextRound)
        assertEquals(2, next.round)
        assertEquals(70L, next.players[0].score)
        assertEquals(0, next.activePlayer)
        assertEquals(3, next.aiDenominator)
        assertTrue(next.players.all { it.captured.isEmpty() && it.hand.size == 8 })
    }

    @Test fun oneCaptureMayCrossTwoThresholdsOrCompleteTwoCategories() {
        val simple = fixture(hand0 = listOf(11), captured0 = (1..9).toList() + 25, boardCards = mapOf(0 to 23))
        val simplePrompt = accepted(simple, Action.Play(11, 0))
        assertEquals(Phase.DECLARE, simplePrompt.phase)
        assertEquals(50, accepted(simplePrompt, Action.Announce).lastAward)
        val simultaneous = fixture(hand0 = listOf(38), captured0 = listOf(37, 39, 40, 41), boardCards = mapOf(0 to 7))
        val simultaneousPrompt = accepted(simultaneous, Action.Play(38, 0))
        assertEquals(Phase.DECLARE, simultaneousPrompt.phase)
        assertEquals(setOf("fire_3", "girls_3"), GameRules.combinations(simultaneousPrompt.players[0].captured).map { it.key }.toSet())
        assertEquals(140, accepted(simultaneousPrompt, Action.Announce).lastAward)
    }

    @Test fun exhaustedHandsEndRoundWithoutAwardAndLeaveEightUndrawnCards() {
        var state = GameEngine.newMatch("exhaustion", 13)
        repeat(100) {
            if (state.phase == Phase.ROUND_END) return@repeat
            val actions = GameEngine.legalActions(state)
            val action = if (state.phase == Phase.DECLARE) Action.Continue else actions.first()
            state = accepted(state, action)
        }
        assertEquals(Phase.ROUND_END, state.phase)
        assertTrue(state.players.all { it.hand.isEmpty() && it.score == 0L })
        assertEquals(8, state.deck.size)
        assertEquals(0, state.lastAward)
        assertNull(state.roundWinner)
    }

    @Test fun staleAndInvalidCommandsPreserveStateAndRandomness() {
        val state = GameEngine.newMatch("id", 7)
        val action = GameEngine.legalActions(state).first()
        assertTrue(GameEngine.apply(state, action, expectedRevision = -1) is Transition.Rejected)
        assertTrue(GameEngine.apply(state, action, expectedMatchId = "other") is Transition.Rejected)
        assertTrue(GameEngine.apply(state, Action.Draw) is Transition.Rejected)
        val after = accepted(state, action)
        assertTrue(GameEngine.apply(after, action, expectedRevision = state.revision) is Transition.Rejected)
        assertEquals(state.rngState, after.rngState)
        assertEquals(0, state.revision)
        assertEquals(state, GameEngine.newMatch("id", 7))
    }

    @Test fun noHiddenHandOrDeckIsExposedToOpponentOrUi() {
        val state = GameEngine.newMatch("hidden", 3)
        val altered = state.copy(players = state.players.mapIndexed { index, player ->
            if (index == 1) player.copy(hand = player.hand.reversed()) else player
        }, deck = state.deck.reversed(), rngState = 999)
        assertEquals(GameEngine.view(state), GameEngine.view(altered))
        assertTrue(GameEngine.legalActions(GameEngine.view(state, 1)).isEmpty())
        val otherTurn = drawAndResolve(accepted(state, GameEngine.legalActions(state).first()))
        assertTrue(GameEngine.legalActions(GameEngine.view(otherTurn, 0)).isEmpty())
        val aiView = GameEngine.view(otherTurn, 1)
        assertEquals(state.players[1].hand, aiView.hand)
        assertEquals(otherTurn.players[0].hand.size, aiView.opponentHandCount)
        assertEquals(GameEngine.legalActions(otherTurn), GameEngine.legalActions(aiView))
        val changedHidden = otherTurn.copy(deck = otherTurn.deck.reversed(), players = otherTurn.players.mapIndexed { index, player ->
            if (index == 0) player.copy(hand = player.hand.reversed()) else player
        })
        val first = (GameEngine.computerStep(otherTurn) as Transition.Accepted).state
        val second = (GameEngine.computerStep(changedHidden) as Transition.Accepted).state
        assertEquals(GameEngine.view(first, 1), GameEngine.view(second, 1))
    }

    @Test fun computerDeclarationUsesSavedRandomnessAndDecreasesItsDenominator() {
        val base = fixture(hand0 = listOf(1, 2, 3, 4), captured0 = listOf(37, 38, 39, 10), boardCards = mapOf(0 to 6))
        val declaring = base.copy(players = base.players.reversed(), activePlayer = 1, phase = Phase.DECLARE, resume = Resume.END_TURN)
        val results = (0L..30L).map { seed ->
            val state = declaring.copy(rngState = seed)
            val result = GameEngine.computerStep(state)
            assertEquals(result, GameEngine.computerStep(state))
            (result as Transition.Accepted).state.also { assertNotEquals(seed, it.rngState) }
        }
        val declined = results.first { it.phase == Phase.HAND }
        assertEquals(2, declined.aiDenominator)
        assertEquals(0L, declined.players[1].score)
        val announced = results.first { it.phase == Phase.ROUND_END }
        assertEquals(70L, announced.players[1].score)
        val certain = (GameEngine.computerStep(declaring.copy(aiDenominator = 1)) as Transition.Accepted).state
        assertEquals(Phase.ROUND_END, certain.phase)
        assertEquals(70, certain.lastAward)
    }

    @Test fun fullOriginalTableAddsARowOnlyWhenRequired() {
        val aries = setOf(7, 19, 27, 38)
        val table = (1..48).filter { it !in aries }.take(14)
        val slots = (0..14).filter { it != 4 }
        val state = fixture(hand0 = listOf(38), boardCards = slots.zip(table).toMap())
        val plays = GameEngine.legalActions(state).filterIsInstance<Action.Play>().filter { it.cardId == 38 }
        assertEquals((15..19).map { Action.Play(38, it) }, plays)
        val expanded = accepted(state, plays.last())
        assertEquals(20, expanded.board.size)
        assertEquals(38, expanded.board[19])
        assertNull(expanded.board[4])
        val other = state.players[1]
        val emptyHuman = other.copy(hand = emptyList(), captured = other.captured + other.hand + state.deck.last())
        val aiState = state.copy(players = listOf(emptyHuman, state.players[0]), deck = state.deck.dropLast(1), activePlayer = 1)
        val aiResult = GameEngine.computerStep(aiState) as Transition.Accepted
        assertEquals(20, aiResult.state.board.size)
        assertEquals(38, aiResult.state.board[15])
    }

    @Test fun corruptedStatesAreRejectedBeforeAnActionCanRun() {
        val state = GameEngine.newMatch("valid", 8)
        val invalid = listOf(
            state.copy(board = state.board.dropLast(1)),
            state.copy(players = state.players.take(1)),
            state.copy(deck = state.deck + state.deck.first()),
            state.copy(drawnCard = state.deck.first()),
            state.copy(aiDenominator = 0),
            state.copy(activePlayer = 1),
            state.copy(resume = Resume.DRAW),
            state.copy(phase = Phase.MATCH_END),
        )
        invalid.forEach {
            assertFalse(GameEngine.validate(it))
            assertTrue(GameEngine.apply(it, Action.Draw) is Transition.Rejected)
        }
    }

    @Test fun inconsistentHandCountsAreRejectedBeforeTheyCanStrandTheNextActor() {
        val state = GameEngine.newMatch("invalid-hands", 118)
        val emptyOpponent = state.copy(players = listOf(
            state.players[0], state.players[1].copy(hand = emptyList(), captured = state.players[1].hand),
        ))
        assertEquals((1..48).toSet(), (emptyOpponent.deck + emptyOpponent.board.filterNotNull() +
            emptyOpponent.players.flatMap { it.hand + it.captured }).toSet())
        assertFalse(GameEngine.validate(emptyOpponent))
        assertTrue(GameEngine.apply(emptyOpponent, Action.Play(state.players[0].hand.first(), 9)) is Transition.Rejected)
        assertTrue(GameEngine.apply(emptyOpponent.copy(activePlayer = 1), Action.Draw) is Transition.Rejected)

        val validDraw = accepted(state, GameEngine.legalActions(state).first())
        val strandedDraw = validDraw.copy(players = listOf(validDraw.players[0],
            validDraw.players[1].copy(hand = emptyList(), captured = validDraw.players[1].captured + validDraw.players[1].hand)))
        assertFalse(GameEngine.validate(strandedDraw))
        assertTrue(GameEngine.apply(strandedDraw, Action.Draw) is Transition.Rejected)
        val drawn = accepted(validDraw, Action.Draw)
        assertFalse(GameEngine.validate(strandedDraw.copy(phase = Phase.DRAW_TARGET, drawnCard = drawn.drawnCard, deck = drawn.deck)))

        val declaring = fixture(hand0 = listOf(38), captured0 = listOf(37, 39, 40, 41), boardCards = mapOf(0 to 7))
        val prompt = accepted(declaring, Action.Play(38, 0))
        val wrongActorPrompt = prompt.copy(activePlayer = 1)
        assertFalse(GameEngine.validate(wrongActorPrompt))
        assertTrue(GameEngine.apply(wrongActorPrompt, Action.Continue) is Transition.Rejected)
        val ended = accepted(prompt, Action.Announce)
        assertFalse(GameEngine.validate(ended.copy(activePlayer = 1, roundWinner = 1)))
    }

    @Test fun deterministicPlayPreservesEveryCardThroughManyCompleteMatches() {
        repeat(120) { seed ->
            val target = if (seed % 12 == 0) 900 else 100
            var state = GameEngine.newMatch("stress-$seed", seed.toLong(), target = target)
            var transitions = 0
            while (state.phase != Phase.MATCH_END && transitions < 6000) {
                val previous = state
                val result = if (state.activePlayer == 1 && state.phase != Phase.ROUND_END) GameEngine.computerStep(state) else {
                    val actions = GameEngine.legalActions(state)
                    assertTrue("seed=$seed phase=${state.phase}", actions.isNotEmpty())
                    val action = if (state.phase == Phase.DECLARE) Action.Announce else actions.first()
                    GameEngine.apply(state, action)
                }
                assertTrue("seed=$seed $result", result is Transition.Accepted)
                state = (result as Transition.Accepted).state
                assertEquals(previous.revision + 1, state.revision)
                assertTrue(GameEngine.validate(state))
                transitions++
            }
            assertEquals("seed=$seed transitions=$transitions", Phase.MATCH_END, state.phase)
            assertTrue(state.players[requireNotNull(state.winner)].score >= target)
        }
    }

    @Test fun trainingKeepsItsOriginalStrategyRegardlessOfRequestedDifficulty() {
        val original = GameEngine.newMatch("training-difficulty", 21, training = true)
        Difficulty.entries.forEach { difficulty ->
            assertEquals(original, GameEngine.newMatch("training-difficulty", 21, training = true, difficulty = difficulty))
        }
        assertEquals(Difficulty.NORMAL, original.difficulty)
        assertFalse(GameEngine.validate(original.copy(difficulty = Difficulty.HARD)))
    }

    @Test fun easyChoicesUseSavedRandomnessAndNeverEndAnEndlessSession() {
        val base = asComputer(fixture(hand0 = listOf(30, 39), captured0 = listOf(37, 38), boardCards = mapOf(0 to 11, 1 to 10)))
            .copy(target = null, difficulty = Difficulty.EASY)
        val choices = (0L..79L).map { seed ->
            val state = base.copy(rngState = seed)
            val result = GameEngine.computerStep(state)
            assertEquals(result, GameEngine.computerStep(state))
            val next = (result as Transition.Accepted).state
            assertTrue(GameEngine.validate(next))
            assertNotEquals(seed, next.rngState)
            assertNotEquals(Phase.SESSION_END, next.phase)
            assertNull(next.winner)
            (state.players[1].hand - next.players[1].hand.toSet()).single()
        }
        assertEquals(setOf(30, 39), choices.toSet())
        val singleChoice = asComputer(fixture(hand0 = listOf(39), boardCards = mapOf(0 to 10)))
            .copy(difficulty = Difficulty.EASY)
        val next = (GameEngine.computerStep(singleChoice) as Transition.Accepted).state
        assertEquals(singleChoice.rngState, next.rngState)
    }

    @Test fun hardOpponentTakesSeventyPointFireInsteadOfFirstUnscoredPair() {
        val state = asComputer(fixture(hand0 = listOf(30, 39), captured0 = listOf(37, 38), boardCards = mapOf(0 to 11, 1 to 10)))
        val normal = (GameEngine.computerStep(state) as Transition.Accepted).state
        assertEquals(listOf(30, 11), normal.players[1].captured.takeLast(2))
        val hard = (GameEngine.computerStep(state.copy(difficulty = Difficulty.HARD)) as Transition.Accepted).state
        assertEquals(listOf(39, 10), hard.players[1].captured.takeLast(2))
        assertEquals(Phase.DECLARE, hard.phase)
        assertEquals(state.rngState, hard.rngState)
        val announced = (GameEngine.computerStep(hard) as Transition.Accepted).state
        assertEquals(70, announced.lastAward)
        assertEquals(70L, announced.players[1].score)
    }

    @Test fun hardTiesPreferGuardiansThenSpecialCardsThenTheFirstLegalPair() {
        val guardian = asComputer(fixture(hand0 = listOf(37, 38), boardCards = mapOf(0 to 6, 1 to 7)))
            .copy(difficulty = Difficulty.HARD)
        assertEquals(listOf(38, 7), (GameEngine.computerStep(guardian) as Transition.Accepted).state.players[1].captured.takeLast(2))
        val special = asComputer(fixture(hand0 = listOf(1, 33), boardCards = mapOf(0 to 13, 1 to 3)))
            .copy(difficulty = Difficulty.HARD)
        assertEquals(listOf(33, 3), (GameEngine.computerStep(special) as Transition.Accepted).state.players[1].captured.takeLast(2))
        val stable = asComputer(fixture(hand0 = listOf(1, 2), boardCards = mapOf(0 to 13, 1 to 14)))
            .copy(difficulty = Difficulty.HARD)
        assertEquals(listOf(1, 13), (GameEngine.computerStep(stable) as Transition.Accepted).state.players[1].captured.takeLast(2))
        val drawn = asComputer(fixture(hand0 = listOf(1), boardCards = mapOf(0 to 10, 1 to 36), drawn = 39, phase = Phase.DRAW_TARGET))
            .copy(difficulty = Difficulty.HARD)
        assertEquals(listOf(39, 36), (GameEngine.computerStep(drawn) as Transition.Accepted).state.players[1].captured.takeLast(2))
    }

    @Test fun allDifficultiesIgnoreHiddenCardIdentitiesAndFutureDeckOrder() {
        val base = asComputer(fixture(hand0 = listOf(30, 39), captured0 = listOf(37, 38), boardCards = mapOf(0 to 11, 1 to 10)))
        Difficulty.entries.forEach { difficulty ->
            val state = base.copy(difficulty = difficulty)
            val other = state.players[0]
            val alternate = state.copy(
                players = listOf(other.copy(hand = other.hand.dropLast(1) + state.deck.last()), state.players[1]),
                deck = state.deck.dropLast(1).reversed() + other.hand.last(),
            )
            assertTrue(GameEngine.validate(alternate))
            assertEquals(GameEngine.view(state, 1), GameEngine.view(alternate, 1))
            val first = (GameEngine.computerStep(state) as Transition.Accepted).state
            val second = (GameEngine.computerStep(alternate) as Transition.Accepted).state
            assertEquals(GameEngine.view(first, 1), GameEngine.view(second, 1))
        }
    }

    @Test fun easyAndHardDeclareImmediatelyWithoutConsumingRandomness() {
        val base = fixture(hand0 = listOf(1, 2, 3, 4), captured0 = listOf(37, 38, 39, 10), boardCards = mapOf(0 to 6))
        val declaring = base.copy(players = base.players.reversed(), activePlayer = 1, phase = Phase.DECLARE, resume = Resume.END_TURN)
        listOf(Difficulty.EASY, Difficulty.HARD).forEach { difficulty ->
            val next = (GameEngine.computerStep(declaring.copy(difficulty = difficulty)) as Transition.Accepted).state
            assertEquals(Phase.ROUND_END, next.phase)
            assertEquals(70L, next.players[1].score)
            assertEquals(declaring.rngState, next.rngState)
        }
    }

    @Test fun endlessScoreCrossesIntAndOldTargetLimitsWithoutWinning() {
        val base = fixture(hand0 = listOf(3), captured0 = listOf(37, 38), boardCards = mapOf(0 to 10), drawn = 39, phase = Phase.DRAW_TARGET)
        val previousScore = Int.MAX_VALUE.toLong() + 1_000_000L
        val endless = base.copy(target = null, players = base.players.map { it.copy(score = previousScore) })
        val announced = accepted(accepted(endless, Action.ResolveDraw(0)), Action.Announce)
        assertEquals(Phase.ROUND_END, announced.phase)
        assertNull(announced.winner)
        assertEquals(previousScore + 70L, announced.players[0].score)
        val next = accepted(announced, Action.NextRound)
        assertNull(next.target)
        assertEquals(previousScore + 70L, next.players[0].score)
        assertEquals(Phase.HAND, next.phase)
    }

    @Test fun endlessPlaysTwoThousandRoundsWithoutAutomaticSessionEnd() {
        var state = GameEngine.newMatch("endless-stress", 45, target = null)
            .let { it.copy(players = it.players.map { player -> player.copy(score = Int.MAX_VALUE.toLong() + 100L) }) }
        var transitions = 0
        while (state.round <= 2000 && transitions < 200_000) {
            val result = if (state.activePlayer == 1 && state.phase != Phase.ROUND_END) GameEngine.computerStep(state) else {
                val action = if (state.phase == Phase.DECLARE) Action.Announce else GameEngine.legalActions(state).first { it != Action.EndSession }
                GameEngine.apply(state, action)
            }
            assertTrue("round=${state.round} $result", result is Transition.Accepted)
            state = (result as Transition.Accepted).state
            assertNull(state.winner)
            assertTrue(state.phase != Phase.MATCH_END && state.phase != Phase.SESSION_END)
            transitions++
        }
        assertEquals(2001, state.round)
        assertTrue(state.players.all { it.score > Int.MAX_VALUE })
        assertTrue(GameEngine.validate(state))
    }

    @Test fun endingSessionAtAnyPhaseOrActorPreservesCardsAndScoresAndRejectsFurtherCommands() {
        val hand = GameEngine.newMatch("end-at-phase", 11, target = null)
        val afterHand = accepted(hand, GameEngine.legalActions(hand).first { it is Action.Play })
        val draw = if (afterHand.phase == Phase.DECLARE) accepted(afterHand, Action.Continue) else afterHand
        val drawn = accepted(draw, Action.Draw)
        val computerTurn = accepted(drawn, GameEngine.legalActions(drawn).first { it is Action.ResolveDraw })
        val preparedDraw = fixture(hand0 = listOf(3), captured0 = listOf(37, 38), boardCards = mapOf(0 to 10), drawn = 39, phase = Phase.DRAW_TARGET).copy(target = null)
        val declaring = accepted(preparedDraw, Action.ResolveDraw(0))
        val roundEnd = accepted(declaring, Action.Announce)
        val computerHand = asComputer(fixture(hand0 = listOf(39), boardCards = mapOf(0 to 10))).copy(target = null)
        listOf(hand, draw, drawn, computerTurn, computerHand, declaring, roundEnd).forEach { state ->
            assertTrue(Action.EndSession in GameEngine.legalActions(GameEngine.view(state, 0)))
            val ended = accepted(state, Action.EndSession)
            assertEquals(Phase.SESSION_END, ended.phase)
            assertNull(ended.winner)
            assertNull(ended.roundWinner)
            assertEquals(0, ended.lastAward)
            assertEquals(state.players, ended.players)
            assertEquals(state.board, ended.board)
            assertEquals(state.deck, ended.deck)
            assertEquals(state.drawnCard, ended.drawnCard)
            assertEquals(state.rngState, ended.rngState)
            assertTrue(GameEngine.legalActions(ended).isEmpty())
            listOf(Action.EndSession, Action.Draw, Action.ResolveDraw(0), Action.Play(1, 0), Action.Announce, Action.Continue, Action.NextRound).forEach {
                assertTrue(GameEngine.apply(ended, it) is Transition.Rejected)
            }
            assertTrue(GameEngine.computerStep(ended) is Transition.Rejected)
        }
        assertTrue(GameEngine.apply(GameEngine.newMatch("finite", 11), Action.EndSession) is Transition.Rejected)
        assertTrue(GameEngine.apply(GameEngine.newMatch("tutorial-end", 11, training = true), Action.EndSession) is Transition.Rejected)
    }

    @Test fun overflowingAnAwardIsRejectedWithoutChangingTheEndlessSnapshot() {
        val base = fixture(hand0 = listOf(3), captured0 = listOf(37, 38), boardCards = mapOf(0 to 10), drawn = 39, phase = Phase.DRAW_TARGET).copy(target = null)
        val declaring = accepted(base, Action.ResolveDraw(0)).let { state ->
            state.copy(players = state.players.mapIndexed { index, player -> if (index == 0) player.copy(score = Long.MAX_VALUE - 60L) else player })
        }
        assertTrue(GameEngine.validate(declaring))
        assertEquals(Transition.Rejected("Достигнут предел счётчика очков"), GameEngine.apply(declaring, Action.Announce))
        assertEquals(Long.MAX_VALUE - 60L, declaring.players[0].score)
        assertTrue(GameEngine.apply(declaring, Action.EndSession) is Transition.Accepted)
    }

    private fun asComputer(state: GameState): GameState {
        val other = state.players[1]
        return state.copy(
            players = listOf(other.copy(hand = other.hand.dropLast(1), captured = other.captured + other.hand.last() + state.deck.last()), state.players[0]),
            deck = state.deck.dropLast(1), activePlayer = 1,
        ).also { assertTrue(it.toString(), GameEngine.validate(it)) }
    }

    private fun fixture(
        hand0: List<Int>,
        captured0: List<Int> = emptyList(),
        boardCards: Map<Int, Int>,
        drawn: Int? = null,
        phase: Phase = Phase.HAND,
    ): GameState {
        val used = hand0 + captured0 + boardCards.values + listOfNotNull(drawn)
        require(used.size == used.toSet().size)
        val remaining = (1..48).filter { it !in used }
        val hand1Size = hand0.size + if (phase == Phase.HAND) 0 else 1
        val hand1 = remaining.take(hand1Size)
        val deckSize = minOf(24, remaining.size - hand1Size).let { maximum ->
            if ((remaining.size - hand1Size - maximum) % 2 == 0) maximum else maximum - 1
        }
        val deck = remaining.drop(hand1.size).take(deckSize)
        val rest = remaining.drop(hand1.size + deck.size)
        val player0 = PlayerState(hand0, captured0, 0)
        val player1 = PlayerState(hand1, rest, 0)
        return GameState("fixture", 0, 1, 900, listOf(player0, player1), List(15) { boardCards[it] }, deck,
            activePlayer = 0, phase = phase, drawnCard = drawn, resume = null, rngState = 0).also { assertTrue(it.toString(), GameEngine.validate(it)) }
    }
}
