package com.example.witches.data

import com.example.witches.core.Action
import com.example.witches.core.Deck
import com.example.witches.core.Difficulty
import com.example.witches.core.GameEngine
import com.example.witches.core.GameState
import com.example.witches.core.Phase
import com.example.witches.core.Transition
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

class GameRecordTest {
    @Test fun legacySchemaRetainsEverySettingAndSelection() {
        for (flags in 0..7) for (selected in -1..8) {
            val bytes = ByteBuffer.allocate(14).putInt(0x5A4F4431).putInt(1).put(flags.toByte()).put(selected.toByte()).putInt(0).array()
            checksum(bytes)
            val migrated = decode(bytes)
            assertEquals(AppSettings(flags and 1 != 0, flags and 2 != 0, flags and 4 != 0), migrated.settings)
            assertEquals(selected.takeUnless { it == -1 }, migrated.selectedCardId)
            assertNull(migrated.game)
            val newBytes = PrototypeCodec.encode(migrated)
            assertEquals(3, PrototypeCodec.schemaNumber(newBytes))
            assertEquals(migrated, decode(newBytes))
        }
    }

    @Test fun schemaTwoFixtureRetainsItsMatchSettingsStatisticsAndRandomness() {
        val old = schemaTwo()
        assertEquals(2, PrototypeCodec.schemaNumber(old))
        val migrated = decode(old)
        assertEquals(AppSettings(true, false, true, true, .35f), migrated.settings)
        assertEquals(3, migrated.selectedCardId)
        assertEquals(MatchStatistics(2, 1, 1, "old-complete"), migrated.stats)
        assertEquals(SessionSlot.AUXILIARY, migrated.activeSlot)
        assertEquals(CampaignProgress(), migrated.progress)
        val game = requireNotNull(migrated.game)
        assertEquals("legacy900", game.matchId)
        assertEquals(900, game.target)
        assertEquals(Difficulty.NORMAL, game.difficulty)
        assertEquals(listOf(120L, 80L), game.players.map { it.score })
        assertEquals((25..32).toList(), game.players[0].hand)
        assertEquals((1..24).toList(), game.deck)
        assertEquals(12345L, game.rngState)
        assertEquals(migrated, decode(PrototypeCodec.encode(migrated)))
    }

    @Test fun everyZonePhaseAndRandomStateRoundTripDuringPlay() {
        var state = GameEngine.newMatch("persisted-match", 45219L)
        repeat(120) {
            val record = PrototypeRecord(AppSettings(false, true, true), 3, state)
            assertEquals(record, decode(PrototypeCodec.encode(record)))
            val actions = GameEngine.legalActions(state)
            if (actions.isEmpty()) return@repeat
            val action = if (state.phase == Phase.DECLARE) Action.Continue else actions.first()
            state = (GameEngine.apply(state, action) as Transition.Accepted).state
        }
    }

    @Test fun restoredComputerStateProducesTheSameNextTransitionForEveryDifficulty() {
        for (difficulty in Difficulty.entries) {
            var game = GameEngine.newMatch("computer-resume-$difficulty", 9211L, difficulty = difficulty)
            while (game.activePlayer != 1) {
                val actions = GameEngine.legalActions(game)
                val action = if (game.phase == Phase.DECLARE) Action.Continue else actions.first()
                game = (GameEngine.apply(game, action) as Transition.Accepted).state
            }
            val restored = requireNotNull(decode(PrototypeCodec.encode(PrototypeRecord(game = game))).game)
            assertEquals(GameEngine.computerStep(game), GameEngine.computerStep(restored))
            assertEquals(game.deck, restored.deck)
            assertEquals(game.rngState, restored.rngState)
        }
    }

    @Test fun campaignUnlocksBothBranchesAndEndlessOnlyAfterTenEasyWins() {
        var progress = CampaignProgress()
        assertTrue(progress.isUnlocked(1, Difficulty.EASY))
        assertFalse(progress.isUnlocked(1, Difficulty.NORMAL))
        assertFalse(progress.isUnlocked(2, Difficulty.EASY))
        assertThrows(IllegalArgumentException::class.java) { progress.withVictory(1, Difficulty.HARD) }
        progress = progress.withVictory(1, Difficulty.EASY)
        assertTrue(progress.isUnlocked(2, Difficulty.EASY))
        assertTrue(progress.isUnlocked(1, Difficulty.NORMAL))
        assertFalse(progress.isUnlocked(1, Difficulty.HARD))
        progress = progress.withVictory(1, Difficulty.NORMAL).withVictory(1, Difficulty.HARD)
        assertTrue(progress.isCompleted(1, Difficulty.HARD))
        for (level in 2..9) progress = progress.withVictory(level, Difficulty.EASY)
        assertFalse(progress.endlessUnlocked)
        progress = progress.withVictory(10, Difficulty.EASY)
        assertTrue(progress.endlessUnlocked)
        assertFalse(progress.isCompleted(10, Difficulty.NORMAL))
        assertEquals(progress, progress.withVictory(10, Difficulty.EASY))
    }

    @Test fun sessionsSurviveSwitchingAndTrainingDoesNotReplaceEitherGame() {
        val progress = CampaignProgress(List(10) { 1 })
        val campaign = GameEngine.newMatch("campaign", 1, 100, difficulty = Difficulty.EASY)
        val endless = GameEngine.newMatch("endless", 2, null, difficulty = Difficulty.HARD)
        val lesson = GameEngine.newMatch("lesson", 0, training = true)
        val record = PrototypeRecord(progress = progress)
            .startSession(SessionConfig(SessionSlot.CAMPAIGN, Difficulty.EASY, 1), campaign)
            .startSession(SessionConfig(SessionSlot.ENDLESS, Difficulty.HARD), endless)
            .startSession(SessionConfig(SessionSlot.AUXILIARY, training = true), lesson)
        val restored = decode(PrototypeCodec.encode(record))
        assertEquals(record, restored)
        assertEquals(lesson, restored.game)
        assertEquals(campaign, restored.selectSession(SessionSlot.CAMPAIGN).game)
        assertEquals(endless, restored.selectSession(SessionSlot.ENDLESS).game)
        assertEquals(record.sessions, restored.sessions)
    }

    @Test fun closedModesAndMismatchedConfigurationsCannotStartOrBeEncoded() {
        val first = GameEngine.newMatch("first", 1, 100, difficulty = Difficulty.EASY)
        assertThrows(IllegalArgumentException::class.java) {
            PrototypeRecord().startSession(SessionConfig(SessionSlot.CAMPAIGN, Difficulty.NORMAL, 1), first)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrototypeRecord().startSession(SessionConfig(SessionSlot.CAMPAIGN, Difficulty.EASY, 2), GameEngine.newMatch("locked", 2, 200, difficulty = Difficulty.EASY))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrototypeRecord().startSession(SessionConfig(SessionSlot.ENDLESS), GameEngine.newMatch("locked-endless", 2, null))
        }
        listOf(CampaignProgress(listOf(1)), CampaignProgress(listOf(0, 1) + List(8) { 0 }), CampaignProgress(listOf(2) + List(9) { 0 })).forEach {
            assertThrows(IllegalArgumentException::class.java) { PrototypeCodec.encode(PrototypeRecord(progress = it)) }
        }
    }

    @Test fun completedResultAndCampaignVictoryAreAtomicAndCountedOnceAcrossSlots() {
        val won = campaignResult("campaign-win", winner = 0)
        val initial = GameEngine.newMatch(won.matchId, 15, 100, difficulty = Difficulty.EASY)
        val once = PrototypeRecord().startSession(SessionConfig(SessionSlot.CAMPAIGN, Difficulty.EASY, 1), initial).withGame(won)
        assertEquals(MatchStatistics(1, 1, 0, won.matchId), once.stats)
        assertTrue(once.progress.isCompleted(1, Difficulty.EASY))
        assertEquals(once, once.withGame(won))
        assertThrows(IllegalArgumentException::class.java) { PrototypeCodec.encode(once.copy(progress = CampaignProgress())) }
        assertThrows(IllegalArgumentException::class.java) {
            PrototypeCodec.encode(once.copy(sessions = once.sessions + (SessionSlot.CAMPAIGN to requireNotNull(once.session).copy(resultRecorded = false))))
        }
        val legacy = tutorialEnd().copy(training = false, matchId = "legacy-result")
        val second = once.startSession(SessionConfig(SessionSlot.AUXILIARY), GameEngine.newMatch(legacy.matchId, 0, 1)).withGame(legacy)
        assertEquals(2, second.stats.completed)
        val switched = decode(PrototypeCodec.encode(second)).selectSession(SessionSlot.CAMPAIGN)
        assertEquals(switched, switched.withGame(won))
        assertEquals(2, switched.withGame(won).stats.completed)
        assertEquals(once.progress, switched.progress)
    }

    @Test fun lossTrainingAndEndlessTerminationDoNotUnlockCampaignOrChangeWins() {
        val lost = campaignResult("campaign-loss", winner = 1)
        val failed = PrototypeRecord().startSession(SessionConfig(SessionSlot.CAMPAIGN, Difficulty.EASY, 1),
            GameEngine.newMatch(lost.matchId, 3, 100, difficulty = Difficulty.EASY)).withGame(lost)
        assertEquals(CampaignProgress(), failed.progress)
        assertEquals(MatchStatistics(1, 0, 1, lost.matchId), failed.stats)
        val trained = failed.startSession(SessionConfig(SessionSlot.AUXILIARY, training = true), GameEngine.newMatch("finished", 0, training = true))
            .withGame(tutorialEnd())
        assertEquals(failed.stats, trained.stats)
        val unlimited = GameEngine.newMatch("unlimited", 5, null, difficulty = Difficulty.HARD).let { game ->
            game.copy(players = game.players.mapIndexed { i, p -> p.copy(score = Int.MAX_VALUE.toLong() + i + 17) })
        }
        val allEasy = CampaignProgress(List(10) { 1 })
        val running = PrototypeRecord(progress = allEasy).startSession(SessionConfig(SessionSlot.ENDLESS, Difficulty.HARD), unlimited)
        val ended = (GameEngine.apply(unlimited, Action.EndSession) as Transition.Accepted).state
        val saved = running.withGame(ended)
        assertEquals(MatchStatistics(), saved.stats)
        assertEquals(allEasy, saved.progress)
        assertEquals(ended, decode(PrototypeCodec.encode(saved)).game)
        assertEquals(saved, saved.withGame(ended))
    }

    @Test fun staleForeignOrChangedGameCannotOverwriteSelectedCampaignSave() {
        val initial = GameEngine.newMatch("guarded", 42, 100, difficulty = Difficulty.EASY)
        val current = (GameEngine.apply(initial, GameEngine.legalActions(initial).first()) as Transition.Accepted).state
        val record = PrototypeRecord().startSession(SessionConfig(SessionSlot.CAMPAIGN, Difficulty.EASY, 1), initial).withGame(current)
        assertThrows(IllegalArgumentException::class.java) { record.withGame(current.copy(matchId = "foreign")) }
        assertThrows(IllegalArgumentException::class.java) { record.withGame(initial) }
        assertThrows(IllegalArgumentException::class.java) { record.withGame(current.copy(difficulty = Difficulty.HARD)) }
        assertEquals(record, record.withGame(current))
        val legacy = PrototypeRecord(game = GameEngine.newMatch("legacy-guard", 7))
        assertThrows(IllegalArgumentException::class.java) { legacy.withGame(requireNotNull(legacy.game).copy(matchId = "other")) }
    }

    @Test fun invalidGameCannotBeWrittenOrRestoredEvenWithValidChecksum() {
        val valid = GameEngine.newMatch("bad-game", 34L)
        assertThrows(IllegalArgumentException::class.java) {
            PrototypeCodec.encode(PrototypeRecord(game = valid.copy(deck = valid.deck.dropLast(1))))
        }
        val bytes = schemaTwo()
        val input = legacyGameInput(bytes)
        input.readInt()
        input.readUTF()
        input.readLong()
        input.readInt()
        val targetOffset = bytes.size - input.available()
        ByteBuffer.wrap(bytes).putInt(targetOffset, 0)
        checksum(bytes)
        assertThrows(InvalidRecordException::class.java) { decode(bytes) }
    }

    @Test fun unknownRulesOversizedRecordsAndNonCanonicalFlagsAreRejected() {
        val bytes = schemaTwo()
        val input = legacyGameInput(bytes)
        ByteBuffer.wrap(bytes).putInt(bytes.size - input.available(), 99)
        checksum(bytes)
        assertThrows(UnsupportedRulesException::class.java) { decode(bytes) }
        assertThrows(InvalidRecordException::class.java) { decode(ByteArray(8193)) }
        val badFlag = schemaTwo()
        val flagInput = DataInputStream(ByteArrayInputStream(badFlag))
        flagInput.skipBytes(22)
        assertTrue(flagInput.readBoolean())
        flagInput.readUTF()
        badFlag[badFlag.size - flagInput.available()] = 2
        checksum(badFlag)
        assertThrows(InvalidRecordException::class.java) { decode(badFlag) }
    }

    @Test fun invalidMusicVolumeAndInconsistentStatisticsAreRejected() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.1f).forEach { volume ->
            assertThrows(IllegalArgumentException::class.java) { PrototypeCodec.encode(PrototypeRecord(settings = AppSettings(musicVolume = volume))) }
            val bytes = PrototypeCodec.encode(PrototypeRecord())
            ByteBuffer.wrap(bytes).putFloat(10, volume)
            checksum(bytes)
            assertThrows(InvalidRecordException::class.java) { decode(bytes) }
        }
        assertThrows(IllegalArgumentException::class.java) { PrototypeCodec.encode(PrototypeRecord(stats = MatchStatistics(completed = 1))) }
        assertThrows(IllegalArgumentException::class.java) { PrototypeCodec.encode(PrototypeRecord(stats = MatchStatistics(1, 2, 0, "bad"))) }
    }

    // A valid end snapshot from the original lesson; the campaign fixture adds a prior 30-point round.
    private fun campaignResult(id: String, winner: Int): GameState {
        val base = tutorialEnd().copy(matchId = id, target = 100, training = false, difficulty = Difficulty.EASY)
        if (winner == 0) return base.copy(players = base.players.mapIndexed { index, player -> if (index == 0) player.copy(score = 100L) else player })
            .also { assertTrue(GameEngine.validate(it)) }
        val human = base.players[1]
        return base.copy(players = listOf(human.copy(hand = human.hand.dropLast(1)), base.players[0].copy(score = 100L)),
            deck = base.deck + human.hand.last(), activePlayer = 1, roundWinner = 1, winner = 1)
            .also { assertTrue(GameEngine.validate(it)) }
    }

    private fun tutorialEnd(): GameState {
        var game = GameEngine.newMatch("finished", 0, training = true)
        fun play(key: String, boardKey: String? = null) {
            val card = game.players[0].hand.first { Deck.card(it).type.key == key }
            val action = GameEngine.legalActions(game).filterIsInstance<Action.Play>().first {
                it.cardId == card && (boardKey == null || game.board[it.slot]?.let(Deck::card)?.type?.key == boardKey)
            }
            game = (GameEngine.apply(game, action) as Transition.Accepted).state
        }
        fun finishTurn() {
            game = (GameEngine.apply(game, Action.Draw) as Transition.Accepted).state
            game = (GameEngine.apply(game, GameEngine.legalActions(game).first()) as Transition.Accepted).state
            repeat(8) { if (game.activePlayer == 1) game = (GameEngine.computerStep(game) as Transition.Accepted).state }
            assertEquals(0, game.activePlayer)
        }
        play("sagittarius_fire", "sagittarius_square"); finishTurn()
        play("cancer_square", "cancer"); finishTurn()
        play("capricorn"); finishTurn()
        play("leo", "leo_fire")
        return (GameEngine.apply(game, Action.Announce) as Transition.Accepted).state
    }

    private fun legacyGameInput(bytes: ByteArray): DataInputStream = DataInputStream(ByteArrayInputStream(bytes)).apply {
        skipBytes(22)
        if (readBoolean()) readUTF()
        assertTrue(readBoolean())
    }
    private fun schemaTwo(): ByteArray = Base64.getDecoder().decode(SCHEMA_TWO)
    private fun decode(bytes: ByteArray) = PrototypeCodec.decode(ByteArrayInputStream(bytes))
    private fun checksum(bytes: ByteArray) {
        ByteBuffer.wrap(bytes).putInt(bytes.size - 4, CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt())
    }
    private companion object {
        // Fixed old wire fixture, constructed independently of the schema3 encoder: flags5, selection3, stats2/1/1, HAND, target900.
        const val SCHEMA_TWO = "Wk9EMQAAAAIFAwAAAAIAAAABAAAAAQEADG9sZC1jb21wbGV0ZQEAAAABAAlsZWdhY3k5MDAAAAAAAAAADAAAAAMAAAOEAAAACAAAABkAAAAaAAAAGwAAABwAAAAdAAAAHgAAAB8AAAAgAAAAAAAAAHgAAAAIAAAAIQAAACIAAAAjAAAAJAAAACUAAAAmAAAAJwAAACgAAAAAAAAAUAAAAA8AAAApAAAAKgAAACsAAAAs/////wAAAC0AAAAuAAAALwAAADD///////////////////////////////8AAAAYAAAAAQAAAAIAAAADAAAABAAAAAUAAAAGAAAABwAAAAgAAAAJAAAACgAAAAsAAAAMAAAADQAAAA4AAAAPAAAAEAAAABEAAAASAAAAEwAAABQAAAAVAAAAFgAAABcAAAAYAAAAAAAESEFORP////8AAAAAAAAAMDkAAAADAAAAAP//////////ABQ+Bd4="
    }
}
