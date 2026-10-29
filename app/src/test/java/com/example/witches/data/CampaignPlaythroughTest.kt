package com.example.witches.data

import com.example.witches.core.Action
import com.example.witches.core.Deck
import com.example.witches.core.Difficulty
import com.example.witches.core.GameEngine
import com.example.witches.core.GameRules
import com.example.witches.core.GameView
import com.example.witches.core.Group
import com.example.witches.core.Phase
import com.example.witches.core.Transition
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CampaignPlaythroughTest {
    @Test fun tenRealEasyWinsUnlockEndlessAndBothSavedSessionsSurviveRestoration() {
        var record = PrototypeRecord()
        var completedMatches = 0
        var computerReplayChecks = 0
        for (level in 1..10) {
            assertTrue(record.progress.isUnlocked(level, Difficulty.EASY))
            assertFalse(record.progress.endlessUnlocked)
            var won = false
            for (attempt in 0 until 16) {
                val progressBefore = record.progress
                val config = SessionConfig(SessionSlot.CAMPAIGN, Difficulty.EASY, level)
                val seed = 0x5A0D0000L + level * 1009L + attempt * 7919L
                val initial = GameEngine.newMatch("proof-level-$level-attempt-$attempt", seed,
                    target = config.target, difficulty = config.difficulty)
                record = record.startSession(config, initial)
                var steps = 0
                var checkedComputerReplay = false
                while (requireNotNull(record.game).phase != Phase.MATCH_END && steps < 6000) {
                    val game = requireNotNull(record.game)
                    if (!checkedComputerReplay && game.activePlayer == 1 && game.phase != Phase.ROUND_END) {
                        val restored = restore(record)
                        assertEquals(record, restored)
                        assertEquals(GameEngine.computerStep(game), GameEngine.computerStep(requireNotNull(restored.game)))
                        checkedComputerReplay = true
                        computerReplayChecks++
                    }
                    record = advance(record)
                    steps++
                }
                val finished = requireNotNull(record.game)
                assertEquals("level=$level attempt=$attempt exceeded 6000 transitions", Phase.MATCH_END, finished.phase)
                assertEquals(level * 100, finished.target)
                assertTrue(finished.players[requireNotNull(finished.winner)].score >= level * 100L)
                assertTrue(record.sessions.getValue(SessionSlot.CAMPAIGN).resultRecorded)
                completedMatches++
                assertEquals(record, record.withGame(finished))
                if (finished.winner == 0) {
                    won = true
                    break
                }
                assertEquals("Losing must not unlock an additional challenge", progressBefore, record.progress)
            }
            assertTrue("No actual win for level=$level in 16 fixed-seed attempts", won)
            assertEquals(List(10) { if (it < level) 1 else 0 }, record.progress.completed)
            assertTrue(record.progress.isUnlocked(level, Difficulty.NORMAL))
            assertFalse(record.progress.isUnlocked(level, Difficulty.HARD))
            record = restore(record).also { assertEquals(record, it) }
        }
        assertEquals(10, record.stats.wins)
        assertEquals(completedMatches, record.stats.completed)
        assertEquals(completedMatches - 10, record.stats.losses)
        assertTrue("The controller must actually play against Will", computerReplayChecks >= 10)
        assertTrue(record.progress.endlessUnlocked)
        (1..10).forEach { level ->
            assertTrue(record.progress.isCompleted(level, Difficulty.EASY))
            assertTrue(record.progress.isUnlocked(level, Difficulty.NORMAL))
            assertFalse(record.progress.isUnlocked(level, Difficulty.HARD))
        }

        val completedCampaign = record.sessions.getValue(SessionSlot.CAMPAIGN)
        val config = SessionConfig(SessionSlot.ENDLESS, Difficulty.NORMAL)
        record = record.startSession(config, GameEngine.newMatch("proof-endless", 0x5A0D05L,
            target = null, difficulty = config.difficulty))
        var endlessSteps = 0
        while (endlessSteps < 64) {
            record = advance(record)
            endlessSteps++
            val game = requireNotNull(record.game)
            assertNull(game.target)
            assertNull(game.winner)
            assertNotEquals(Phase.MATCH_END, game.phase)
            assertNotEquals(Phase.SESSION_END, game.phase)
            assertEquals(completedCampaign, record.sessions.getValue(SessionSlot.CAMPAIGN))
            if (endlessSteps >= 12 && game.activePlayer == 0 && game.phase == Phase.HAND) break
        }
        assertTrue(endlessSteps in 12..64)
        val endless = record.sessions.getValue(SessionSlot.ENDLESS)
        assertTrue(endless.game.revision >= 12L)
        assertEquals(0, endless.game.activePlayer)
        assertEquals(Phase.HAND, endless.game.phase)
        val paused = restore(record)
        assertEquals(record, paused)
        val campaignSelected = paused.selectSession(SessionSlot.CAMPAIGN)
        assertEquals(completedCampaign.game, campaignSelected.game)
        assertEquals(endless, campaignSelected.sessions.getValue(SessionSlot.ENDLESS))
        record = campaignSelected.selectSession(SessionSlot.ENDLESS)
        assertEquals(paused, record)

        // Test-only, reachable snapshot for isolated AVD verification; never included in production assets.
        val evidence = File("build/test-evidence/v05-unlocked.bin").absoluteFile
        val directory = requireNotNull(evidence.parentFile)
        assertTrue(directory.isDirectory || directory.mkdirs())
        evidence.writeBytes(PrototypeCodec.encode(record))
        assertEquals(record, PrototypeCodec.decode(ByteArrayInputStream(evidence.readBytes())))
        var roundEnd = record
        var roundSteps = 0
        while (requireNotNull(roundEnd.game).phase != Phase.ROUND_END && roundSteps < 128) {
            roundEnd = advance(roundEnd)
            roundSteps++
        }
        assertEquals(Phase.ROUND_END, requireNotNull(roundEnd.game).phase)
        assertEquals(completedCampaign, roundEnd.sessions.getValue(SessionSlot.CAMPAIGN))
        File(directory, "v05-endless-roundend.bin").writeBytes(PrototypeCodec.encode(roundEnd))
        assertEquals(roundEnd, restore(roundEnd))
        println("Campaign playthrough: 10 real wins, $completedMatches completed matches, $computerReplayChecks restored AI replays; evidence=${evidence.path}")
    }

    private fun restore(record: PrototypeRecord): PrototypeRecord =
        PrototypeCodec.decode(ByteArrayInputStream(PrototypeCodec.encode(record)))

    private fun advance(record: PrototypeRecord): PrototypeRecord {
        val game = requireNotNull(record.game)
        val result = if (game.activePlayer == 1 && game.phase != Phase.ROUND_END) GameEngine.computerStep(game)
            else GameEngine.apply(game, publicHumanAction(GameEngine.view(game, 0)))
        assertTrue("round=${game.round} phase=${game.phase}: $result", result is Transition.Accepted)
        val next = (result as Transition.Accepted).state
        assertEquals(game.revision + 1, next.revision)
        assertTrue(GameEngine.validate(next))
        return record.withGame(next)
    }

    /** The scripted player sees exactly the information exposed to the real UI. */
    private fun publicHumanAction(view: GameView): Action {
        val legal = GameEngine.legalActions(view).filterNot { it == Action.EndSession }
        return when (view.phase) {
            Phase.HAND, Phase.DRAW_TARGET -> {
                fun pair(action: Action): List<Int> {
                    val (id, slot) = when (action) {
                        is Action.Play -> action.cardId to action.slot
                        is Action.ResolveDraw -> requireNotNull(view.drawnCard) to action.slot
                        Action.Draw, Action.Announce, Action.Continue, Action.NextRound, Action.EndSession -> return emptyList()
                    }
                    return view.board.getOrNull(slot)?.let { listOf(id, it) }.orEmpty()
                }
                legal.filter { pair(it).isNotEmpty() }.maxWithOrNull(compareBy<Action>(
                    { GameRules.combinations(view.ownCaptured + pair(it)).sumOf { combination -> combination.points } },
                    { pair(it).count { id -> Deck.card(id).type.guardian != null } },
                    { pair(it).count { id -> Deck.card(id).type.group != Group.SIMPLE } },
                )) ?: legal.first()
            }
            Phase.DRAW -> Action.Draw
            Phase.DECLARE -> Action.Announce
            Phase.ROUND_END -> Action.NextRound
            Phase.MATCH_END, Phase.SESSION_END -> error("The scripted player cannot act after a session ends")
        }
    }
}
