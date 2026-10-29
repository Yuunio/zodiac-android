package com.example.witches.data

import com.example.witches.core.Deck
import com.example.witches.core.GameView
import com.example.witches.core.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComputerMoveExplanationTest {
    @Test fun captureNamesOnlyTheNewPublicPairEvenWhenTheTurnChanges() {
        val fire = card("sagittarius_fire")
        val square = card("sagittarius_square")
        val before = view().copy(board = listOf(square) + List(14) { null }, opponentCaptured = listOf(1, 2))
        val after = before.copy(revision = 8, actor = 0, board = List(15) { null },
            opponentHandCount = 7, opponentCaptured = listOf(1, 2, fire, square))
        assertEquals("Вилл забрала пару: Стрелец (Огонь) и Стрелец (Квадрат).", describeComputerMove(before, after))
        assertEquals("Вилл забрала пару: Стрелец (Огонь) и Стрелец (Квадрат). Собрана новая комбинация.",
            describeComputerMove(before, after.copy(actor = 1, phase = Phase.DECLARE)))
    }

    @Test fun drawAndPlacementNameOnlyTheCardWhichIsAlreadyVisible() {
        val water = card("pisces_water")
        val before = view(Phase.DRAW)
        val opened = before.copy(revision = 8, phase = Phase.DRAW_TARGET, drawnCard = water, deckCount = 23)
        assertEquals("Вилл открыла из колоды: Рыбы (Вода, Ирма).", describeComputerMove(before, opened))
        val placed = opened.copy(revision = 9, actor = 0, phase = Phase.HAND, drawnCard = null,
            board = listOf(water) + List(14) { null })
        assertEquals("Вилл положила на свободное место: Рыбы (Вода, Ирма).", describeComputerMove(opened, placed))
    }

    @Test fun declarationDescribesTheAcceptedResultWithoutInventingAMotive() {
        val before = view(Phase.DECLARE)
        assertEquals("Вилл объявила комбинации: +70 очков. Розыгрыш завершён.", describeComputerMove(before,
            before.copy(revision = 8, phase = Phase.ROUND_END, roundWinner = 1, lastAward = 70)))
        assertEquals("Вилл продолжила розыгрыш и пока не начисляет себе очки.", describeComputerMove(before,
            before.copy(revision = 8, phase = Phase.DRAW)))
    }

    @Test fun staleForeignAndOpponentPrivateViewsHaveNoExplanation() {
        val before = view(Phase.DRAW)
        val after = before.copy(revision = 8, phase = Phase.DRAW_TARGET, drawnCard = card("aries_fire"))
        assertNull(describeComputerMove(before, after.copy(matchId = "another-match")))
        assertNull(describeComputerMove(before, after.copy(revision = 9)))
        assertNull(describeComputerMove(before.copy(viewer = 1), after.copy(viewer = 1)))
        assertNull(describeComputerMove(before.copy(actor = 0), after))
    }

    private fun card(key: String): Int = Deck.cards.first { it.type.key == key }.id

    private fun view(phase: Phase = Phase.HAND) = GameView(
        matchId = "public-turn", revision = 7, round = 1, target = 900, actor = 1, phase = phase,
        hand = emptyList(), opponentHandCount = 8, board = List(15) { null }, deckCount = 24,
        drawnCard = null, ownCaptured = emptyList(), opponentCaptured = emptyList(), scores = listOf(0, 0),
        lastAward = 0, roundWinner = null, winner = null, training = false, aiDenominator = 3,
    )
}
