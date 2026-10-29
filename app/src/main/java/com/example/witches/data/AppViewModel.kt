package com.example.witches.data

import android.app.Application
import android.util.AtomicFile
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.witches.core.Action
import com.example.witches.core.Deck
import com.example.witches.core.GameEngine
import com.example.witches.core.GameState
import com.example.witches.core.GameView
import com.example.witches.core.Phase
import com.example.witches.core.Transition
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private object PrototypeSerializer : Serializer<PrototypeRecord> {
    override val defaultValue = PrototypeRecord()
    override suspend fun readFrom(input: InputStream): PrototypeRecord = try {
        PrototypeCodec.decode(input)
    } catch (error: InvalidRecordException) {
        throw CorruptionException(error.message ?: "Сохранение повреждено", error)
    }
    override suspend fun writeTo(t: PrototypeRecord, output: OutputStream) { output.write(PrototypeCodec.encode(t)) }
}

private class PrototypeRepository(application: Application) {
    val notice = MutableStateFlow<String?>(null)
    private val recordFile = File(application.filesDir, "datastore/prototype.bin")
    private val backupFile = File(application.filesDir, "datastore/prototype.backup")
    private val writes = Mutex()
    private val store = DataStoreFactory.create(
        serializer = PrototypeSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { error ->
            recordFile.copyTo(File(recordFile.parentFile, "prototype-unreadable-${System.nanoTime()}.bin"))
            val backup = try { AtomicFile(backupFile).openRead().use(PrototypeCodec::decode) } catch (_: IOException) { null }
            val reason = if (error.cause is UnsupportedSchemaException || error.cause is UnsupportedRulesException) {
                "Версия сохранения не поддерживается."
            } else "Файл сохранения повреждён."
            notice.value = "$reason Оригинал сохранён на устройстве. " +
                if (backup != null) "Восстановлена локальная резервная копия." else "Резервная копия недоступна; применены начальные настройки."
            backup ?: PrototypeRecord()
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        produceFile = { recordFile },
    )
    val records = store.data.map<PrototypeRecord, PrototypeRecord?> { it }.onStart { emit(null) }
        .retryWhen { error, _ ->
            if (error !is IOException) false else {
                notice.value = "Не удалось прочитать сохранение. Проверьте свободное место и доступ к файлам, затем закройте сообщение для повторной попытки."
                notice.first { it == null }
                true
            }
        }

    suspend fun currentGame(): GameState? = try { store.data.first().game } catch (_: IOException) {
        notice.value = "Не удалось прочитать партию. Откройте приложение заново."
        null
    }

    suspend fun update(transform: (PrototypeRecord) -> PrototypeRecord): Boolean = writes.withLock {
        try {
            withContext(Dispatchers.IO) { preserveLegacy() }
            val saved = store.updateData { transform(it) }
            try {
                withContext(Dispatchers.IO) { writeBackup(saved) }
            } catch (_: IOException) {
                notice.value = "Изменения сохранены, но локальную резервную копию обновить не удалось."
            }
            true
        } catch (_: IOException) {
            notice.value = "Не удалось сохранить изменения. Проверьте свободное место на устройстве."
            false
        }
    }

    suspend fun applyAction(action: Action, id: String, revision: Long): MovePresentation? {
        var presentation: MovePresentation? = null
        var rejection: String? = null
        val written = update { record ->
            val game = record.game
            if (game == null) {
                rejection = "Сохранённой партии нет."
                record
            } else when (val result = GameEngine.apply(game, action, id, revision)) {
                is Transition.Accepted -> {
                    presentation = presentMove(GameEngine.view(game), GameEngine.view(result.state))
                    record.withGame(result.state)
                }
                is Transition.Rejected -> { rejection = result.reason; record }
            }
        }
        if (rejection != null) notice.value = rejection
        return presentation.takeIf { written }
    }

    suspend fun applyComputer(expected: GameState, result: Transition): Boolean {
        if (result is Transition.Rejected) {
            notice.value = result.reason
            return false
        }
        val next = (result as Transition.Accepted).state
        var accepted = false
        val written = update { record ->
            val current = record.game
            if (current?.matchId == expected.matchId && current.revision == expected.revision) {
                accepted = true
                record.withGame(next)
            } else record
        }
        return written && accepted
    }

    private fun preserveLegacy() {
        if (!recordFile.exists()) return
        val bytes = recordFile.readBytes()
        val schema = PrototypeCodec.schemaNumber(bytes)
        if (schema !in 1..2) return
        val original = File(recordFile.parentFile, "prototype-schema${schema}-original.bin")
        if (!original.exists()) {
            val archive = AtomicFile(original)
            val output = archive.startWrite()
            try { output.write(bytes); archive.finishWrite(output) }
            catch (error: IOException) { archive.failWrite(output); throw error }
        }
    }

    private fun writeBackup(record: PrototypeRecord) {
        val backup = AtomicFile(backupFile)
        val output = backup.startWrite()
        try {
            output.write(PrototypeCodec.encode(record))
            backup.finishWrite(output)
            if (backup.openRead().use(PrototypeCodec::decode) != record) throw IOException("Резервная копия не прошла проверку")
        } catch (error: IOException) {
            backup.failWrite(output)
            throw error
        }
    }

    companion object {
        @Volatile private var instance: PrototypeRepository? = null
        fun get(application: Application): PrototypeRepository = instance ?: synchronized(this) {
            instance ?: PrototypeRepository(application).also { instance = it }
        }
    }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PrototypeRepository.get(application)
    private val pending = MutableStateFlow(0)
    private val computerMessage = MutableStateFlow<ComputerMoveMessage?>(null)
    private val presentation = MutableStateFlow<MovePresentation?>(null)
    private var presentationEpoch = 0L
    private var inputJob: Job? = null
    private var computerJob: Job? = null
    private var computerAllowed = false

    val state: StateFlow<PrototypeUiState> = repository.records.map {
        if (it == null) PrototypeUiState() else PrototypeUiState(loaded = true, settings = it.settings, selectedCardId = it.selectedCardId,
            game = it.game?.let { game -> GameEngine.view(game, 0) }, stats = it.stats,
            activeSlot = it.activeSlot, progress = it.progress, sessionConfig = it.session?.config,
            sessions = it.sessions.mapValues { (_, session) -> SessionView(session.config, GameEngine.view(session.game)) })
    }.combine(repository.notice) { state, message -> state.copy(message = message) }
        .combine(pending) { state, count -> state.copy(busy = count > 0) }
        .combine(computerMessage) { state, message ->
            state.copy(computerMessage = message?.takeIf {
                it.matchId == state.game?.matchId && it.revision == state.game.revision
            }?.text)
        }
        .combine(presentation) { state, move ->
            state.copy(busy = state.busy || move != null, presentation = move?.takeIf {
                it.after.matchId == state.game?.matchId && it.after.revision == state.game.revision
            })
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PrototypeUiState())

    fun updateSettings(settings: AppSettings) = launchInput { repository.update { it.copy(settings = settings) } }

    fun selectCard(id: Int?) {
        if (id != null && id !in 0..8) { repository.notice.value = "Эта демонстрационная карта недоступна."; return }
        launchInput { repository.update { it.copy(selectedCardId = id) } }
    }

    fun dismissMessage() {
        repository.notice.value = null
        viewModelScope.launch {
            computerJob?.join()
            if (computerAllowed) resumeComputerTurn()
        }
    }

    fun startGame(training: Boolean = false) {
        startSession(SessionConfig(SessionSlot.AUXILIARY, training = training))
    }

    fun startSession(config: SessionConfig) {
        pauseComputer()
        val epoch = presentationEpoch
        launchInput {
            val game = GameEngine.newMatch(UUID.randomUUID().toString(), System.nanoTime(),
                target = config.target, training = config.training, difficulty = config.difficulty)
            if (repository.update { it.startSession(config, game) } && epoch == presentationEpoch) {
                presentation.value = MovePresentation(GameEngine.view(game), MoveKind.DEAL)
            }
        }
    }

    fun selectSession(slot: SessionSlot) {
        pauseComputer()
        launchInput { repository.update { it.selectSession(slot) } }
    }

    fun submit(action: Action, expectedMatchId: String, expectedRevision: Long) {
        val current = state.value.game ?: return
        if (presentation.value != null || current.actor != 0 && action != Action.NextRound && action != Action.EndSession) return
        computerMessage.value = null
        val epoch = presentationEpoch
        launchInput {
            val move = repository.applyAction(action, expectedMatchId, expectedRevision)
            if (epoch == presentationEpoch) presentation.value = move
        }
    }

    fun completePresentation(matchId: String, revision: Long) {
        val move = presentation.value ?: return
        if (move.after.matchId != matchId || move.after.revision != revision) return
        presentation.value = null
        if (computerAllowed) resumeComputerTurn()
    }

    fun resumeComputerTurn() {
        computerAllowed = true
        if (!state.value.loaded || computerJob != null || inputJob?.isActive == true) return
        val current = state.value.game ?: return
        if (!needsAutomaticStep(current)) return
        pending.update { it + 1 }
        val nextJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                while (computerAllowed) {
                    presentation.first { it == null }
                    val game = repository.currentGame() ?: break
                    if (!needsAutomaticStep(GameEngine.view(game))) break
                    if (game.target == null && game.phase == Phase.ROUND_END) {
                        delay(2400)
                        if (!computerAllowed) break
                        presentation.value = repository.applyAction(Action.NextRound, game.matchId, game.revision)
                        if (presentation.value == null) break
                        continue
                    }
                    delay(if (state.value.settings.reducedMotion) 120 else 500)
                    val result = withContext(Dispatchers.Default) {
                        currentCoroutineContext().ensureActive()
                        GameEngine.computerStep(game)
                    }
                    currentCoroutineContext().ensureActive()
                    if (!computerAllowed) break
                    val accepted = repository.applyComputer(game, result)
                    currentCoroutineContext().ensureActive()
                    if (!computerAllowed || !accepted) {
                        computerMessage.value = null
                        break
                    }
                    val next = (result as Transition.Accepted).state
                    presentation.value = presentMove(GameEngine.view(game), GameEngine.view(next))
                    computerMessage.value = describeComputerMove(GameEngine.view(game, 0), GameEngine.view(next, 0))?.let {
                        ComputerMoveMessage(next.matchId, next.revision, it)
                    }
                }
            } finally {
                val wasCancelled = !currentCoroutineContext().isActive
                computerJob = null
                pending.update { it - 1 }
                if (wasCancelled && computerAllowed && repository.notice.value == null) viewModelScope.launch {
                    yield()
                    if (computerAllowed) resumeComputerTurn()
                }
            }
        }
        computerJob = nextJob
        nextJob.start()
    }

    fun pauseComputer() {
        computerAllowed = false
        presentationEpoch++
        computerJob?.cancel()
        computerMessage.value = null
        presentation.value = null
    }

    private fun needsAutomaticStep(game: GameView): Boolean =
        game.phase !in listOf(Phase.MATCH_END, Phase.SESSION_END) &&
            (game.target == null && game.phase == Phase.ROUND_END || game.actor == 1 && game.phase != Phase.ROUND_END)

    private fun launchInput(block: suspend () -> Unit) {
        if (!state.value.loaded || inputJob?.isActive == true) return
        pending.update { it + 1 }
        inputJob = viewModelScope.launch {
            try { block() } catch (error: IllegalArgumentException) {
                repository.notice.value = error.message ?: "Не удалось выполнить действие."
            } finally {
                inputJob = null
                pending.update { it - 1 }
                if (computerAllowed) resumeComputerTurn()
            }
        }
    }
}

private data class ComputerMoveMessage(val matchId: String, val revision: Long, val text: String)

/** Describes only cards which became public in an already persisted transition. */
internal fun describeComputerMove(before: GameView, after: GameView): String? {
    if (before.viewer != 0 || after.viewer != 0 || before.actor != 1 || before.matchId != after.matchId ||
        after.revision != before.revision + 1) return null
    fun label(id: Int): String = Deck.card(id).type.let { type ->
        "${type.sign.label} (${type.group.label}${type.guardian?.let { ", ${it.label}" }.orEmpty()})"
    }
    return when (before.phase) {
        Phase.HAND, Phase.DRAW_TARGET -> {
            val captured = after.opponentCaptured.filterNot { it in before.opponentCaptured }
            if (captured.size == 2) {
                "Вилл забрала пару: ${captured.joinToString(" и ", transform = ::label)}." +
                    if (after.phase == Phase.DECLARE) " Собрана новая комбинация." else ""
            } else {
                val placed = after.board.filterNotNull().firstOrNull { it !in before.board }
                placed?.let { "Вилл положила на свободное место: ${label(it)}." }
            }
        }
        Phase.DRAW -> after.drawnCard?.let { "Вилл открыла из колоды: ${label(it)}." }
        Phase.DECLARE -> if (after.roundWinner == 1 && after.phase in listOf(Phase.ROUND_END, Phase.MATCH_END)) {
            "Вилл объявила комбинации: +${after.lastAward} очков. Розыгрыш завершён."
        } else "Вилл продолжила розыгрыш и пока не начисляет себе очки."
        Phase.ROUND_END, Phase.MATCH_END, Phase.SESSION_END -> null
    }
}
