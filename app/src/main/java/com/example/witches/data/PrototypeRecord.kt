package com.example.witches.data

import com.example.witches.core.Difficulty
import com.example.witches.core.GameEngine
import com.example.witches.core.GameState
import com.example.witches.core.GameView
import com.example.witches.core.Phase
import com.example.witches.core.PlayerState
import com.example.witches.core.Resume
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.UTFDataFormatException
import java.util.zip.CRC32

data class AppSettings(
    val soundEffects: Boolean = true,
    val haptics: Boolean = true,
    val reducedMotion: Boolean = false,
    val musicEnabled: Boolean = true,
    val musicVolume: Float = .35f,
)

enum class SessionSlot { CAMPAIGN, ENDLESS, AUXILIARY }

data class SessionConfig(
    val slot: SessionSlot,
    val difficulty: Difficulty = Difficulty.NORMAL,
    val level: Int? = null,
    val training: Boolean = false,
) {
    val target: Int? get() = when (slot) {
        SessionSlot.CAMPAIGN -> requireNotNull(level) * 100
        SessionSlot.ENDLESS -> null
        SessionSlot.AUXILIARY -> if (training) 1 else 900
    }
    internal fun isValid(): Boolean = when (slot) {
        SessionSlot.CAMPAIGN -> level != null && level in 1..10 && !training
        SessionSlot.ENDLESS -> level == null && !training
        SessionSlot.AUXILIARY -> level == null && (!training || difficulty == Difficulty.NORMAL)
    }
}

data class CampaignProgress(val completed: List<Int> = List(10) { 0 }) {
    fun isCompleted(level: Int, difficulty: Difficulty): Boolean = level in 1..10 &&
        completed.getOrNull(level - 1)?.let { it and (1 shl difficulty.ordinal) != 0 } == true
    fun isUnlocked(level: Int, difficulty: Difficulty): Boolean {
        if (level !in 1..10) return false
        return when (difficulty) {
            Difficulty.EASY -> level == 1 || isCompleted(level - 1, Difficulty.EASY)
            Difficulty.NORMAL -> isCompleted(level, Difficulty.EASY)
            Difficulty.HARD -> isCompleted(level, Difficulty.NORMAL)
        }
    }
    val endlessUnlocked: Boolean get() = (1..10).all { isCompleted(it, Difficulty.EASY) }
    fun withVictory(level: Int, difficulty: Difficulty): CampaignProgress {
        require(isValid() && isUnlocked(level, difficulty)) { "Испытание ещё не открыто" }
        return copy(completed = completed.mapIndexed { index, flags ->
            if (index == level - 1) flags or (1 shl difficulty.ordinal) else flags
        })
    }
    internal fun isValid(): Boolean = completed.size == 10 && completed.indices.all { index ->
        val flags = completed[index]
        flags in listOf(0, 1, 3, 7) && (flags == 0 || index == 0 || completed[index - 1] and 1 != 0)
    }
}

data class SessionView(val config: SessionConfig, val game: GameView)

data class MatchStatistics(
    val completed: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val lastCompletedMatchId: String? = null,
)

data class PrototypeUiState(
    val loaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val selectedCardId: Int? = null,
    val message: String? = null,
    val game: GameView? = null,
    val busy: Boolean = false,
    val stats: MatchStatistics = MatchStatistics(),
    val computerMessage: String? = null,
    val progress: CampaignProgress = CampaignProgress(),
    val activeSlot: SessionSlot = SessionSlot.AUXILIARY,
    val sessionConfig: SessionConfig? = null,
    val sessions: Map<SessionSlot, SessionView> = emptyMap(),
    val presentation: MovePresentation? = null,
)

internal data class SavedSession(val config: SessionConfig, val game: GameState, val resultRecorded: Boolean = false)

internal data class PrototypeRecord(
    val settings: AppSettings = AppSettings(),
    val selectedCardId: Int? = null,
    val stats: MatchStatistics = MatchStatistics(),
    val activeSlot: SessionSlot = SessionSlot.AUXILIARY,
    val sessions: Map<SessionSlot, SavedSession> = emptyMap(),
    val progress: CampaignProgress = CampaignProgress(),
) {
    constructor(settings: AppSettings = AppSettings(), selectedCardId: Int? = null, game: GameState?, stats: MatchStatistics = MatchStatistics()) :
        this(settings, selectedCardId, stats, sessions = game?.let { mapOf(SessionSlot.AUXILIARY to SavedSession(
            SessionConfig(SessionSlot.AUXILIARY, it.difficulty, training = it.training), it,
            resultRecorded = it.phase == Phase.MATCH_END || it.phase == Phase.SESSION_END,
        )) }.orEmpty())

    val session: SavedSession? get() = sessions[activeSlot]
    val game: GameState? get() = session?.game

    fun selectSession(slot: SessionSlot): PrototypeRecord {
        require(slot in sessions) { "Сохранённой партии нет" }
        return copy(activeSlot = slot)
    }
    fun startSession(config: SessionConfig, game: GameState): PrototypeRecord {
        require(config.isValid() && matches(config, game) && GameEngine.validate(game)) { "Некорректная новая партия" }
        require(game.phase != Phase.MATCH_END && game.phase != Phase.SESSION_END) { "Нельзя начать завершённую партию" }
        require(sessions.none { (slot, saved) -> slot != config.slot && saved.game.matchId == game.matchId }) { "ID партии уже используется" }
        require(when (config.slot) {
            SessionSlot.CAMPAIGN -> progress.isUnlocked(requireNotNull(config.level), config.difficulty)
            SessionSlot.ENDLESS -> progress.endlessUnlocked
            SessionSlot.AUXILIARY -> true
        }) { "Этот режим или уровень ещё закрыт" }
        return copy(activeSlot = config.slot, sessions = sessions + (config.slot to SavedSession(config, game)))
    }
    fun withGame(next: GameState): PrototypeRecord {
        require(GameEngine.validate(next)) { "Некорректное состояние партии" }
        val previous = session?.takeIf { it.game.matchId == next.matchId }
        require(previous != null || session == null && activeSlot == SessionSlot.AUXILIARY) { "Партия не соответствует выбранному сохранению" }
        val config = previous?.config ?: SessionConfig(SessionSlot.AUXILIARY, next.difficulty, training = next.training)
        require(matches(config, next)) { "Параметры сохранённой партии изменились" }
        require(previous == null || next.revision >= previous.game.revision) { "Сохранённый ход устарел" }
        require(previous == null || next.revision != previous.game.revision || next == previous.game) { "Содержимое сохранённого хода изменилось" }
        require(previous?.resultRecorded != true || next == previous.game) { "Партия уже завершена" }
        val terminal = next.phase == Phase.MATCH_END || next.phase == Phase.SESSION_END
        val firstResult = terminal && previous?.resultRecorded != true
        val countResult = firstResult && next.phase == Phase.MATCH_END && !next.training
        val nextStats = if (countResult) stats.copy(
            completed = Math.addExact(stats.completed, 1),
            wins = Math.addExact(stats.wins, if (next.winner == 0) 1 else 0),
            losses = Math.addExact(stats.losses, if (next.winner == 1) 1 else 0),
            lastCompletedMatchId = next.matchId,
        ) else stats
        val nextProgress = if (firstResult && config.slot == SessionSlot.CAMPAIGN && next.winner == 0)
            progress.withVictory(requireNotNull(config.level), config.difficulty) else progress
        return copy(stats = nextStats, progress = nextProgress,
            sessions = sessions + (activeSlot to SavedSession(config, next, previous?.resultRecorded == true || terminal)))
    }
}

private fun matches(config: SessionConfig, game: GameState): Boolean = config.isValid() &&
    config.difficulty == game.difficulty && config.training == game.training && when (config.slot) {
        SessionSlot.CAMPAIGN -> game.target == config.target
        SessionSlot.ENDLESS -> game.target == null
        SessionSlot.AUXILIARY -> game.target != null
    }

internal open class InvalidRecordException(message: String, cause: Throwable? = null) : IOException(message, cause)
internal class UnsupportedSchemaException : InvalidRecordException("Неподдерживаемая версия сохранения")
internal class UnsupportedRulesException : InvalidRecordException("Неподдерживаемая версия правил сохранения")

/** Schema 3 keeps independent sessions; schema 1/2 remain readable without discarding their data. */
internal object PrototypeCodec {
    private const val MAGIC = 0x5A4F4431
    private const val SCHEMA = 3
    private const val RULES = 2
    private const val MAX_BYTES = 8192

    fun schemaNumber(bytes: ByteArray): Int? = if (bytes.size >= 8) DataInputStream(ByteArrayInputStream(bytes)).let {
        if (it.readInt() == MAGIC) it.readInt() else null
    } else null

    fun encode(record: PrototypeRecord): ByteArray {
        require(valid(record)) { "Некорректное сохранение" }
        val payload = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeInt(MAGIC)
                writeInt(SCHEMA)
                writeByte((if (record.settings.soundEffects) 1 else 0) or
                    (if (record.settings.haptics) 2 else 0) or (if (record.settings.reducedMotion) 4 else 0) or
                    (if (record.settings.musicEnabled) 8 else 0))
                writeByte(record.selectedCardId ?: -1)
                writeFloat(record.settings.musicVolume)
                writeInt(record.stats.completed)
                writeInt(record.stats.wins)
                writeInt(record.stats.losses)
                writeNullableString(record.stats.lastCompletedMatchId)
                writeUTF(record.activeSlot.name)
                record.progress.completed.forEach(::writeByte)
                SessionSlot.entries.forEach { slot ->
                    val session = record.sessions[slot]
                    writeBoolean(session != null)
                    if (session != null) {
                        writeUTF(session.config.difficulty.name)
                        writeInt(session.config.level ?: -1)
                        writeBoolean(session.config.training)
                        writeBoolean(session.resultRecorded)
                        writeGame(session.game)
                    }
                }
            }
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(payload)
            DataOutputStream(this).writeInt(CRC32().apply { update(payload) }.value.toInt())
        }.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(input: InputStream): PrototypeRecord = try {
        val raw = ByteArrayOutputStream()
        val buffer = ByteArray(512)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val byte = input.read()
                if (byte < 0) break
                raw.write(byte)
            } else raw.write(buffer, 0, count)
            if (raw.size() > MAX_BYTES) throw InvalidRecordException("Сохранение превышает допустимый размер")
        }
        val bytes = raw.toByteArray()
        if (bytes.size < 14) throw InvalidRecordException("Файл сохранения обрезан")
        val payload = bytes.copyOf(bytes.size - 4)
        val checksum = DataInputStream(ByteArrayInputStream(bytes, bytes.size - 4, 4)).readInt()
        if (checksum != CRC32().apply { update(payload) }.value.toInt()) throw InvalidRecordException("Нарушена целостность сохранения")
        val fields = DataInputStream(ByteArrayInputStream(payload))
        if (fields.readInt() != MAGIC) throw InvalidRecordException("Неизвестный формат сохранения")
        val schema = fields.readInt()
        if (schema !in 1..SCHEMA) throw UnsupportedSchemaException()
        val flags = fields.readUnsignedByte()
        val selected = fields.readByte().toInt()
        if (flags !in 0..(if (schema < 3) 7 else 15) || selected !in -1..8) throw InvalidRecordException("Некорректные настройки")
        val settings = AppSettings(flags and 1 != 0, flags and 2 != 0, flags and 4 != 0,
            if (schema < 3) true else flags and 8 != 0, if (schema < 3) .35f else fields.readFloat())
        val record = when (schema) {
            1 -> PrototypeRecord(settings, selected.takeUnless { it == -1 })
            2 -> {
                val stats = fields.readStats()
                PrototypeRecord(settings, selected.takeUnless { it == -1 }, if (fields.readFlag()) fields.readGame(legacy = true) else null, stats)
            }
            3 -> {
                val stats = fields.readStats()
                val active = enumValueOf<SessionSlot>(fields.readUTF())
                val progress = CampaignProgress(List(10) { fields.readUnsignedByte() })
                val sessions = buildMap {
                    SessionSlot.entries.forEach { slot -> if (fields.readFlag()) {
                        val config = SessionConfig(slot, enumValueOf<Difficulty>(fields.readUTF()), fields.readInt().takeUnless { it == -1 }, fields.readFlag())
                        val counted = fields.readFlag()
                        put(slot, SavedSession(config, fields.readGame(legacy = false), counted))
                    } }
                }
                PrototypeRecord(settings, selected.takeUnless { it == -1 }, stats, active, sessions, progress)
            }
            else -> throw UnsupportedSchemaException()
        }
        if (fields.available() != 0) throw InvalidRecordException("Лишние данные в сохранении")
        if (!valid(record)) throw InvalidRecordException("Нарушены правила сохранённых партий или прогресса")
        record
    } catch (error: EOFException) {
        throw InvalidRecordException("Файл сохранения обрезан", error)
    } catch (error: UTFDataFormatException) {
        throw InvalidRecordException("Некорректный текст в сохранении", error)
    } catch (error: IllegalArgumentException) {
        throw InvalidRecordException("Некорректные параметры сохранения", error)
    }

    private fun valid(record: PrototypeRecord): Boolean {
        if (record.selectedCardId != null && record.selectedCardId !in 0..8 || !validStats(record.stats) || !record.progress.isValid()) return false
        if (!record.settings.musicVolume.isFinite() || record.settings.musicVolume !in 0f..1f) return false
        if (record.sessions.isEmpty()) return record.activeSlot == SessionSlot.AUXILIARY
        if (record.activeSlot !in record.sessions || record.sessions.values.map { it.game.matchId }.distinct().size != record.sessions.size) return false
        return record.sessions.all { (slot, session) ->
            val game = session.game
            val terminal = game.phase == Phase.MATCH_END || game.phase == Phase.SESSION_END
            slot == session.config.slot && matches(session.config, game) && GameEngine.validate(game) &&
                session.resultRecorded == terminal &&
                when (slot) {
                    SessionSlot.CAMPAIGN -> record.progress.isUnlocked(requireNotNull(session.config.level), session.config.difficulty) &&
                        (!session.resultRecorded || game.winner != 0 || record.progress.isCompleted(requireNotNull(session.config.level), session.config.difficulty))
                    SessionSlot.ENDLESS -> record.progress.endlessUnlocked
                    SessionSlot.AUXILIARY -> true
                }
        }
    }
    private fun validStats(stats: MatchStatistics): Boolean = stats.completed >= 0 && stats.wins >= 0 && stats.losses >= 0 &&
        stats.wins.toLong() + stats.losses <= stats.completed.toLong() &&
        ((stats.completed == 0) == (stats.lastCompletedMatchId == null)) &&
        (stats.lastCompletedMatchId == null || stats.lastCompletedMatchId.isNotBlank() && stats.lastCompletedMatchId.length <= 128)
    private fun DataInputStream.readStats(): MatchStatistics = MatchStatistics(readInt(), readInt(), readInt(), readNullableString()).also {
        if (!validStats(it)) throw InvalidRecordException("Некорректная статистика")
    }
    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }
    private fun DataInputStream.readFlag(): Boolean = when (readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw InvalidRecordException("Некорректный флаг сохранения")
    }
    private fun DataInputStream.readNullableString(): String? = if (readFlag()) readUTF().also {
        if (it.isBlank() || it.length > 128) throw InvalidRecordException("Некорректный ID партии")
    } else null
    private fun DataOutputStream.writeCards(cards: List<Int>) {
        writeInt(cards.size)
        cards.forEach(::writeInt)
    }
    private fun DataInputStream.readCards(limit: Int): List<Int> {
        val count = readInt()
        if (count !in 0..limit) throw InvalidRecordException("Некорректное количество карт")
        return List(count) { readInt() }
    }
    private fun DataOutputStream.writeGame(game: GameState) {
        writeInt(RULES)
        writeUTF(game.matchId)
        writeLong(game.revision)
        writeInt(game.round)
        writeBoolean(game.target != null)
        game.target?.let(::writeInt)
        game.players.forEach { writeCards(it.hand); writeCards(it.captured); writeLong(it.score) }
        writeCards(game.board.map { it ?: -1 })
        writeCards(game.deck)
        writeInt(game.activePlayer)
        writeUTF(game.phase.name)
        writeInt(game.drawnCard ?: -1)
        writeNullableString(game.resume?.name)
        writeLong(game.rngState)
        writeInt(game.aiDenominator)
        writeInt(game.lastAward)
        writeInt(game.roundWinner ?: -1)
        writeInt(game.winner ?: -1)
        writeBoolean(game.training)
        writeUTF(game.difficulty.name)
    }
    private fun DataInputStream.readGame(legacy: Boolean): GameState {
        if (readInt() != if (legacy) 1 else RULES) throw UnsupportedRulesException()
        val state = GameState(
            matchId = readUTF(), revision = readLong(), round = readInt(),
            target = if (legacy) readInt() else if (readFlag()) readInt() else null,
            players = List(2) { PlayerState(readCards(8), readCards(48), if (legacy) readInt().toLong() else readLong()) },
            board = readCards(50).map { if (it == -1) null else it }, deck = readCards(24),
            activePlayer = readInt(), phase = Phase.valueOf(readUTF()),
            drawnCard = readInt().takeUnless { it == -1 }, resume = readNullableString()?.let(Resume::valueOf),
            rngState = readLong(), aiDenominator = readInt(), lastAward = readInt(),
            roundWinner = readInt().takeUnless { it == -1 }, winner = readInt().takeUnless { it == -1 }, training = readFlag(),
            difficulty = if (legacy) Difficulty.NORMAL else Difficulty.valueOf(readUTF()),
        )
        if (!GameEngine.validate(state)) throw InvalidRecordException("Нарушены правила сохранённой партии")
        return state
    }
}
