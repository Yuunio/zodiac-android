package com.example.witches.ui

import android.media.AudioAttributes
import android.media.SoundPool
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.witches.R
import com.example.witches.data.AppSettings
import com.example.witches.data.PrototypeUiState
import com.example.witches.data.SessionConfig
import com.example.witches.data.SessionSlot
import com.example.witches.audio.MusicController
import com.example.witches.audio.MusicTrack
import com.example.witches.core.Action
import com.example.witches.core.Difficulty
import com.example.witches.core.Phase
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal val Night = Color(0xFF070F28)
internal val Panel = Color(0xFF111F40)
internal val Ink = Color(0xFFF7EEDC)
internal val Muted = Color(0xFFB5C3DE)
internal val Gold = Color(0xFFE7C784)
internal val Line = Color(0xFF506384)
internal val Serif = FontFamily(Font(R.font.zodiac_serif))
private val ZodiacColors = darkColorScheme(
    primary = Gold, onPrimary = Night, secondary = Color(0xFF9CB8F5),
    background = Night, onBackground = Ink, surface = Panel, onSurface = Ink,
    onSurfaceVariant = Muted, outline = Line,
)
private val CardNames = listOf("Солнце", "Луна", "Звезда", "Орбита", "Кристалл", "Комета", "Созвездие", "Сияние", "Затмение")
private val CardColors = listOf(
    Color(0xFF863E48), Color(0xFF425E99), Color(0xFF6E4C91),
    Color(0xFF366F70), Color(0xFF7753A1), Color(0xFFA26336),
    Color(0xFF3D6C9B), Color(0xFF947039), Color(0xFF46517B),
)

@Composable
fun ZodiacApp(
    state: PrototypeUiState,
    onSettingsChange: (AppSettings) -> Unit,
    onSelectCard: (Int?) -> Unit,
    onDismissMessage: () -> Unit,
    onNewGame: (Boolean) -> Unit,
    onAction: (Action, String, Long) -> Unit,
    onResumeComputer: () -> Unit,
    onPauseComputer: () -> Unit,
    onStartSession: (SessionConfig) -> Unit = { onNewGame(it.training) },
    onSelectSession: (SessionSlot) -> Unit = {},
    onPresentationComplete: (String, Long) -> Unit = { _, _ -> },
) {
    var page by rememberSaveable { mutableStateOf("menu") }
    var detailId by rememberSaveable { mutableStateOf<Int?>(null) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var pendingConfig by remember { mutableStateOf<SessionConfig?>(null) }
    var endingSession by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val music = remember(context.applicationContext) { MusicController(context.applicationContext) }
    val latestSettings by rememberUpdatedState(state.settings)
    val latestTrack by rememberUpdatedState(if (page == "game") MusicTrack.GAME else MusicTrack.MENU)
    val latestPauseComputer by rememberUpdatedState(onPauseComputer)
    val owner = LocalLifecycleOwner.current
    var foreground by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner, music) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) foreground = true
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                foreground = false
                latestPauseComputer()
                music.update(latestTrack, latestSettings.musicEnabled, latestSettings.musicVolume, active = false)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); onPauseComputer() }
    }
    LaunchedEffect(page, paused, foreground, state.game?.matchId, state.game?.revision, state.message) {
        if (page != "game" || paused || !foreground || state.message != null || state.game?.let { com.example.witches.core.GameEngine.tutorialStep(it)?.restartRequired } == true) onPauseComputer()
        else onResumeComputer()
    }
    val launchSession: (SessionConfig) -> Unit = { config ->
        onStartSession(config); paused = false; page = "game"
    }
    val startSession: (SessionConfig) -> Unit = { config ->
        if (state.sessions[config.slot]?.isOngoing() == true) pendingConfig = config else launchSession(config)
    }
    val start: (Boolean) -> Unit = { training ->
        if (training) startSession(SessionConfig(SessionSlot.AUXILIARY, training = true))
        else page = if (state.sessionConfig?.slot == SessionSlot.ENDLESS) "endless" else "campaign"
    }
    DisposableEffect(music) { onDispose { music.close() } }
    LaunchedEffect(music, state.loaded, state.settings.musicEnabled, state.settings.musicVolume, page, paused, foreground) {
        music.update(if (page == "game") MusicTrack.GAME else MusicTrack.MENU,
            state.loaded && state.settings.musicEnabled, state.settings.musicVolume, foreground && !paused)
    }
    BackHandler(page != "menu" || detailId != null) {
        if (detailId != null) detailId = null else if (page == "game") paused = true else page = "menu"
    }
    MaterialTheme(colorScheme = ZodiacColors) {
        val playSound = rememberSelectionSound()
        val haptics = LocalHapticFeedback.current
        val select: (Int) -> Unit = { id ->
            if (state.settings.soundEffects) playSound()
            if (state.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onSelectCard(if (state.selectedCardId == id) null else id)
        }
        Box(Modifier.fillMaxSize().background(Night)) {
            Starfield(Modifier.matchParentSize())
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (!state.loaded) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Gold)
                        Text("Открываем звёздный атлас…", Modifier.padding(top = 16.dp), color = Ink)
                    }
                } else when (page) {
                    "menu" -> Home(onNavigate = { page = it }, canContinue = state.game?.phase?.let { it !in listOf(Phase.MATCH_END, Phase.SESSION_END) } == true,
                        busy = state.busy, onStart = { start(false) }, modifier = Modifier.fillMaxSize(),
                        onAuxiliaryResume = state.sessions[SessionSlot.AUXILIARY]?.takeIf {
                            it.isOngoing() && state.activeSlot != SessionSlot.AUXILIARY
                        }?.let { { onSelectSession(SessionSlot.AUXILIARY); page = "game" } })
                    "campaign" -> CampaignScreen(state, startSession,
                        { onSelectSession(SessionSlot.CAMPAIGN); page = "game" }, { page = "menu" }, Modifier.fillMaxSize())
                    "endless" -> EndlessScreen(state, startSession,
                        { onSelectSession(SessionSlot.ENDLESS); page = "game" }, { page = "menu" }, Modifier.fillMaxSize())
                    "game" -> state.game?.let { game ->
                        MatchScreen(game, state.busy, { onAction(it, game.matchId, game.revision) }, { paused = true },
                            { page = "menu" }, start, {
                                if (state.settings.soundEffects) playSound()
                                if (state.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }, state.computerMessage, Modifier.fillMaxSize(),
                            reducedMotion = state.settings.reducedMotion, presentation = state.presentation,
                            onPresentationComplete = onPresentationComplete,
                            matchTitle = state.sessionConfig?.let { config -> when (config.slot) {
                                SessionSlot.CAMPAIGN -> "Уровень ${config.level} · ${config.difficulty.label}"
                                SessionSlot.ENDLESS -> "Бесконечная · ${config.difficulty.label}"
                                SessionSlot.AUXILIARY -> if (config.training) "Учебная партия" else "Классическая партия"
                            } },
                            onNextLevel = state.sessionConfig?.takeIf { it.slot == SessionSlot.CAMPAIGN && game.winner == 0 &&
                                requireNotNull(it.level) < 10 }?.let { config -> {
                                startSession(SessionConfig(SessionSlot.CAMPAIGN, Difficulty.EASY, requireNotNull(config.level) + 1))
                            } })
                    } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    "cards" -> CardCatalog(onBack = { page = "menu" }, feedback = {
                        if (state.settings.soundEffects) playSound()
                        if (state.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }, modifier = Modifier.fillMaxSize(), reducedMotion = state.settings.reducedMotion)
                    "settings" -> Settings(state.settings, onSettingsChange, { page = "menu" }, Modifier.fillMaxSize())
                    "rules" -> Information("Как играть", { page = "menu" }, Modifier.fillMaxSize()) {
                        SectionTitle("Соберите и объявите комбинации")
                        Paragraph("В прохождении цель растёт от 100 до 1000 очков. Победа на лёгкой открывает следующий уровень и среднюю сложность текущего; победа на средней — сложную. После десяти лёгких побед открывается бесконечная игра без предела очков. В колоде 48 карт: 24 простые, 12 геометрических и 12 стихийных. Пять стихийных карт также считаются чародейками.")
                        InfoPanel("Две фазы хода", "Выберите карту руки, затем одну карту стола того же знака — обе попадут в ваш пул. Если у выбранной карты нет пары, положите её на подсвеченное пустое место. Затем откройте центральную колоду и так же разыграйте открытую карту. После этого ходит Вилл.")
                        Paragraph("Рука не пополняется. На старте по 8 карт у вас, у Вилл и на столе. Несколько совпадений дают выбор одной пары. Цвет и крупный рисунок не ограничивают захват — сравнивается знак.")
                        InfoPanel("Объявить или продолжить", "После новой комбинации можно завершить розыгрыш и получить сумму всех собранных комбинаций. При отказе новое объявление доступно только после следующей новой комбинации или порога. Если обе руки закончатся без объявления, никто не получит очки.")
                        SectionTitle("Очки за комбинации")
                        Paragraph("Треугольник: 3 карты — 50. Квадрат: 3 — 40, 4 — 80. Пятиугольник: 3 — 30, 4 — 50, 5 — 100. Любая стихия: 3 — 70. Чародейки: 3 — 70, 4 — 150, 5 — 200. Простые карты: 10 — 40, каждая следующая добавляет 10.")
                        Paragraph("Внутри одной категории учитывается старший достигнутый порог. Разные категории складываются; карта чародейки одновременно учитывается в своей стихии. После объявления новая раздача снова начинается с вашего хода.")
                        Button(onClick = { start(true) }, enabled = !state.busy) { Text("Учебная партия") }
                        Paragraph("Обучение использует исходную фиксированную раздачу и тот же движок. Оно завершается первым объявлением, не меняет статистику и не заменяет сохранения двух основных режимов.")
                        Paragraph("Касание выбирает карту, повторное снимает выбор. Удержание открывает подробности. Пауза сохраняет партию. При редком переполнении стола добавляется ряд: это защита от аварийной остановки оригинала.")
                    }
                    else -> Information("Об игре", { page = "menu" }, Modifier.fillMaxSize()) {
                        SectionTitle("Зодиак")
                        Paragraph("Независимая офлайн-адаптация карточной мини-игры из W.I.T.C.H. / «Чародейки». Правила восстановлены по исходным Lua-скриптам установленной PC-игры.")
                        InfoPanel("Свои рисунки. Локальные данные.", "Все иллюстрации созданы геометрией прямо в приложении. Оригинальные портреты, логотипы, музыка и озвучка не включены.")
                        Paragraph("Партия, настройки и результаты сохраняются на устройстве. Приложение работает без интернета и аккаунта. Перенос на телефон сохраняет фазы, захват по знаку и таблицу очков; рисунки и управление созданы заново.")
                        Paragraph("Эта сборка не является официальным продуктом правообладателей W.I.T.C.H. Перемешивание карт и порядок равнозначных ходов соперника отличаются от PC-реализации.")
                        Text("ВЕРСИЯ 0.5", color = Gold, fontSize = 12.sp, letterSpacing = 1.sp)
                    }
                }
            }
        }
        if (paused && page == "game") AlertDialog(onDismissRequest = { paused = false }, title = { Text("Пауза") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Партия сохраняется после каждого принятого действия. Выход в меню сохранит её для продолжения.")
                if (state.game != null && state.game.target == null && state.game.phase != Phase.SESSION_END) {
                    OutlinedButton(onClick = { endingSession = true }, enabled = !state.busy) { Text("Завершить игру") }
                }
            } },
            confirmButton = { TextButton(onClick = { paused = false }) { Text("Продолжить партию") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { paused = false; page = "rules" }) { Text("Правила") }
                    TextButton(onClick = { paused = false; page = "menu" }) { Text("В меню") }
                }
            })
        pendingConfig?.let { config -> AlertDialog(onDismissRequest = { pendingConfig = null }, title = { Text("Начать новую партию?") },
            text = { Text("Незавершённая партия этого режима будет заменена. Сохранение другого режима и пройденные испытания останутся.") },
            confirmButton = { TextButton(onClick = { pendingConfig = null; launchSession(config) }) { Text("Начать заново") } },
            dismissButton = { TextButton(onClick = { pendingConfig = null }) { Text("Отмена") } }) }
        if (endingSession) AlertDialog(onDismissRequest = { endingSession = false }, title = { Text("Завершить бесконечную игру?") },
            text = { Text("Будет сохранён итоговый счёт. Продолжить эту сессию после завершения нельзя.") },
            confirmButton = { TextButton(onClick = {
                state.game?.let { onAction(Action.EndSession, it.matchId, it.revision) }
                endingSession = false; paused = false
            }, enabled = !state.busy) { Text("Завершить") } },
            dismissButton = { TextButton(onClick = { endingSession = false }) { Text("Отмена") } })
        detailId?.let { id ->
            if (id in CardNames.indices) AlertDialog(
                onDismissRequest = { detailId = null },
                title = { Text(CardNames[id], fontFamily = Serif, color = Gold) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CardArt(id, false, Modifier.width(150.dp).aspectRatio(0.7f))
                        Text("Авторский образец № ${id + 1}. Рисунок и цвет служат оформлению. Игровые свойства этой карте не назначены.")
                    }
                },
                confirmButton = { TextButton(onClick = { detailId = null }) { Text("Закрыть") } },
            )
        }
        state.message?.let { message ->
            AlertDialog(
                onDismissRequest = onDismissMessage, title = { Text("Локальное хранилище") },
                text = { Text(message, Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = onDismissMessage) { Text("Понятно") } },
            )
        }
    }
}

@Composable
private fun Home(onNavigate: (String) -> Unit, canContinue: Boolean, busy: Boolean, onStart: () -> Unit, modifier: Modifier = Modifier,
    onAuxiliaryResume: (() -> Unit)? = null) {
    BoxWithConstraints(modifier) {
        val wide = maxWidth > 660.dp
        if (wide) Row(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Hero(Modifier.weight(1f))
            HomeActions(onNavigate, canContinue, busy, onStart, Modifier.weight(1f).widthIn(max = 420.dp), onAuxiliaryResume)
        } else Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Hero(Modifier.widthIn(max = 420.dp).fillMaxWidth())
            Spacer(Modifier.height(24.dp))
            HomeActions(onNavigate, canContinue, busy, onStart, Modifier.widthIn(max = 420.dp).fillMaxWidth(), onAuxiliaryResume)
        }
    }
}

@Composable
private fun Hero(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("КАРТОЧНЫЙ ЗВЁЗДНЫЙ АТЛАС", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp, textAlign = TextAlign.Center)
        Text("ЗОДИАК", Modifier.padding(top = 12.dp).semantics { heading() }, fontFamily = Serif,
            fontSize = 44.sp, color = Gold, letterSpacing = 3.sp, textAlign = TextAlign.Center)
        Text("Среди звёзд у каждой карты своя история", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
        Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(210.dp)) {
                drawCircle(Gold.copy(alpha = .2f), radius = size.minDimension * .46f, style = Stroke(1.dp.toPx()))
                drawCircle(Gold.copy(alpha = .1f), radius = size.minDimension * .38f, style = Stroke(1.dp.toPx()))
            }
            CardArt(1, false, Modifier.offset(x = (-66).dp, y = 9.dp).rotate(-16f).width(83.dp).height(124.dp))
            CardArt(2, false, Modifier.offset(x = 66.dp, y = 9.dp).rotate(16f).width(83.dp).height(124.dp))
            CardArt(0, false, Modifier.width(96.dp).height(144.dp))
        }
        Text("10 УРОВНЕЙ · ТРИ СЛОЖНОСТИ", Modifier.border(1.dp, Line, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 7.dp),
            fontSize = 10.sp, color = Gold, letterSpacing = 1.5.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun HomeActions(onNavigate: (String) -> Unit, canContinue: Boolean, busy: Boolean, onStart: () -> Unit, modifier: Modifier = Modifier,
    onAuxiliaryResume: (() -> Unit)? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onStart, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(14.dp)) {
            Text("Прохождение", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(onClick = { onNavigate("endless") }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Бесконечная игра") }
        OutlinedButton(onClick = { onNavigate("game") }, enabled = canContinue && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Продолжить") }
        onAuxiliaryResume?.let { resume -> TextButton(onClick = resume, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Продолжить прежнюю / учебную партию")
        } }
        TextButton(onClick = { onNavigate("cards") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Посмотреть карты", color = Muted) }
        TextButton(onClick = { onNavigate("rules") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Как играть", color = Ink, fontSize = 16.sp) }
        HorizontalDivider(color = Line.copy(alpha = .5f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onNavigate("settings") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Настройки", color = Muted) }
            TextButton(onClick = { onNavigate("about") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Об игре", color = Muted) }
        }
        Text("БЕЗ ИНТЕРНЕТА · БЕЗ РЕКЛАМЫ", Modifier.fillMaxWidth().padding(top = 8.dp), color = Muted,
            fontSize = 10.sp, letterSpacing = 1.5.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CardGallery(
    selectedId: Int?, reducedMotion: Boolean, onSelect: (Int) -> Unit, onDetail: (Int) -> Unit,
    onBack: () -> Unit, wide: Boolean, boardWidth: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 16.dp)) {
        PageHeader("Визуальный макет", onBack)
        if (wide) Row(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Board(selectedId, reducedMotion, onSelect, onDetail, Modifier.width(boardWidth))
            }
            GalleryCaption(selectedId, onDetail, Modifier.weight(1f))
        } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("АВТОРСКИЕ ОБРАЗЦЫ · 9 КАРТ", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(vertical = 12.dp))
            Board(selectedId, reducedMotion, onSelect, onDetail, Modifier.width(boardWidth))
            Spacer(Modifier.height(16.dp))
            GalleryCaption(selectedId, onDetail, Modifier.widthIn(max = 400.dp))
        }
    }
}

@Composable
private fun Board(selectedId: Int?, reducedMotion: Boolean, onSelect: (Int) -> Unit, onDetail: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { col ->
                    val id = row * 3 + col
                    val border by animateColorAsState(
                        if (selectedId == id) Gold else Line,
                        animationSpec = if (reducedMotion) snap() else tween(120), label = "cardBorder$id",
                    )
                    CardArt(id, selectedId == id, Modifier.weight(1f).aspectRatio(.7f)
                        .clip(RoundedCornerShape(12.dp))
                        .border(if (selectedId == id) 3.dp else 1.dp, border, RoundedCornerShape(12.dp))
                        .combinedClickable(
                            role = Role.Button, onClickLabel = "Выбрать или снять выбор", onLongClickLabel = "Подробности карты",
                            onClick = { onSelect(id) }, onLongClick = { onDetail(id) },
                        ).semantics {
                            contentDescription = "Карта ${id + 1}. ${CardNames[id]}"
                            selected = selectedId == id
                        })
                }
            }
        }
    }
}

@Composable
private fun GalleryCaption(selectedId: Int?, onDetail: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(selectedId?.let { "Выбрана карта: ${CardNames[it]}" } ?: "Коснитесь любой карты", fontFamily = Serif,
            fontSize = 20.sp, color = Gold, textAlign = TextAlign.Center)
        Text("Повторное касание снимает выбор", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
        OutlinedButton(onClick = { selectedId?.let(onDetail) }, enabled = selectedId != null, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Подробности карты")
        }
        Text("Это просмотр оформления. Здесь нет ходов, очков и игровых эффектов.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Settings(settings: AppSettings, onChange: (AppSettings) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var musicVolume by remember(settings.musicVolume) { mutableFloatStateOf(settings.musicVolume) }
    Information("Настройки", onBack, modifier) {
        Paragraph("Настройки сохраняются на этом устройстве и действуют в меню и партии.")
        SettingToggle("Фоновая музыка", "Две собственные магические темы без вокала", settings.musicEnabled) { onChange(settings.copy(musicEnabled = it)) }
        Text("Громкость музыки: ${(musicVolume * 100).toInt()}%", color = Ink)
        Slider(value = musicVolume, onValueChange = { musicVolume = it },
            onValueChangeFinished = { onChange(settings.copy(musicVolume = musicVolume)) },
            enabled = settings.musicEnabled, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Громкость музыки" })
        SettingToggle("Звук эффектов", "Короткий звук при выборе карты", settings.soundEffects) { onChange(settings.copy(soundEffects = it)) }
        SettingToggle("Вибрация", "Тактильный отклик при касании", settings.haptics) { onChange(settings.copy(haptics = it)) }
        SettingToggle("Меньше анимаций", "Без полётов, вращений и вспышек на поле", settings.reducedMotion) { onChange(settings.copy(reducedMotion = it)) }
        InfoPanel("Музыка остаётся в игре", "При паузе, сворачивании и отключении наушников музыка останавливается. Звук эффектов настраивается отдельно.")
    }
}

@Composable
private fun SettingToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 16.sp)
            Text(subtitle, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Switch(checked, onChange, Modifier.semantics { contentDescription = title })
    }
    HorizontalDivider(color = Line.copy(alpha = .5f))
}

@Composable
private fun Information(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(horizontal = 16.dp)) {
        PageHeader(title, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).widthIn(max = 620.dp).align(Alignment.CenterHorizontally)
            .padding(horizontal = 8.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("Назад", color = Muted) }
        Text(title, modifier = Modifier.weight(1f).padding(start = 12.dp).semantics { heading() }, fontFamily = Serif, color = Gold, fontSize = 24.sp)
    }
    HorizontalDivider(color = Line.copy(alpha = .6f))
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontFamily = Serif, fontSize = 24.sp, color = Gold, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Paragraph(text: String) { Text(text, color = Muted, fontSize = 16.sp, lineHeight = 24.sp) }

@Composable
private fun InfoPanel(title: String, text: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).border(1.dp, Line.copy(alpha = .6f), RoundedCornerShape(16.dp))
        .padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(text, color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
    }
}

@Composable
private fun Starfield(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Brush.radialGradient(listOf(Color(0xFF17386A), Night), center = Offset(size.width * .75f, size.height * .24f), radius = size.maxDimension * .85f))
        repeat(85) { i ->
            val point = Offset(((i * 73 + 29) % 997) / 997f * size.width, ((i * 139 + 71) % 991) / 991f * size.height)
            drawCircle(Ink.copy(alpha = if (i % 7 == 0) .6f else .2f), if (i % 7 == 0) 1.2.dp.toPx() else .6.dp.toPx(), point)
        }
        val center = Offset(size.width * .9f, size.height * .2f)
        drawCircle(Gold.copy(alpha = .07f), size.width * .7f, center, style = Stroke(1.dp.toPx()))
        drawCircle(Gold.copy(alpha = .05f), size.width * .8f, center, style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun CardArt(id: Int, selected: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(CardColors[id], Night)))) {
        val w = size.width
        val h = size.height
        val frame = Path().apply {
            moveTo(w * .14f, h * .06f); lineTo(w * .86f, h * .06f); lineTo(w * .94f, h * .12f)
            lineTo(w * .94f, h * .88f); lineTo(w * .86f, h * .94f); lineTo(w * .14f, h * .94f)
            lineTo(w * .06f, h * .88f); lineTo(w * .06f, h * .12f); close()
        }
        drawPath(frame, Gold.copy(alpha = if (selected) 1f else .7f), style = Stroke(w * .012f))
        drawCircle(Gold.copy(alpha = .14f), w * .37f, Offset(w / 2, h / 2), style = Stroke(w * .008f))
        val center = Offset(w / 2, h / 2)
        val r = w * .25f
        when (id) {
            0 -> {
                drawCircle(Gold, r * .52f, center, style = Stroke(w * .025f))
                repeat(12) { ray ->
                    val a = ray * PI / 6
                    drawLine(Gold, center + Offset(cos(a).toFloat(), sin(a).toFloat()) * r * .72f,
                        center + Offset(cos(a).toFloat(), sin(a).toFloat()) * r, w * .018f)
                }
            }
            1 -> {
                val crescent = Path().apply {
                    moveTo(center.x + r * .55f, center.y - r)
                    cubicTo(center.x - r * 1.5f, center.y - r, center.x - r * 1.5f, center.y + r, center.x + r * .55f, center.y + r)
                    cubicTo(center.x - r * .5f, center.y + r * .6f, center.x - r * .5f, center.y - r * .6f, center.x + r * .55f, center.y - r)
                }
                drawPath(crescent, Gold)
            }
            2, 7 -> star(center, r, if (id == 2) 5 else 8, Gold)
            3 -> {
                drawOval(Gold, Offset(center.x - r * 1.3f, center.y - r * .5f), Size(r * 2.6f, r), style = Stroke(w * .018f))
                drawCircle(Gold, r * .4f, center, style = Stroke(w * .018f))
                drawCircle(Ink, r * .13f, Offset(center.x + r, center.y - r * .32f))
            }
            4 -> {
                val diamond = Path().apply { moveTo(center.x, center.y-r); lineTo(center.x+r*.65f,center.y); lineTo(center.x,center.y+r); lineTo(center.x-r*.65f,center.y); close() }
                drawPath(diamond, Gold, style = Stroke(w * .025f))
                drawLine(Gold, Offset(center.x-r*.65f,center.y),Offset(center.x+r*.65f,center.y),w*.012f)
                drawLine(Gold, Offset(center.x,center.y-r),Offset(center.x,center.y+r),w*.012f)
            }
            5 -> {
                star(center + Offset(r * .4f, r * .3f), r * .6f, 4, Gold)
                repeat(3) { line -> drawLine(Gold.copy(alpha=.8f), center + Offset(-r, -r + line*r*.3f), center + Offset(r*.05f, r*.05f+line*r*.15f), w*.015f) }
            }
            6 -> {
                val points = listOf(Offset(-.9f,.7f),Offset(-.4f,-.1f),Offset(.2f,.3f),Offset(.85f,-.8f))
                points.zipWithNext().forEach { (a,b) -> drawLine(Gold,center+a*r,center+b*r,w*.015f) }
                points.forEach { drawCircle(Gold,r*.12f,center+it*r) }
            }
            else -> {
                drawCircle(Gold, r * .85f, center, style = Stroke(w * .028f))
                drawArc(Gold, -70f, 145f, false, Offset(center.x-r*1.1f,center.y-r*1.1f),Size(r*2.2f,r*2.2f),style=Stroke(w*.012f))
            }
        }
        star(Offset(w * .2f, h * .17f), w * .045f, 4, Gold)
        star(Offset(w * .8f, h * .83f), w * .045f, 4, Gold)
        repeat(id + 1) { dot -> drawCircle(Gold.copy(alpha = .75f), w * .012f, Offset(w * .5f + (dot-id/2f)*w*.035f, h*.85f)) }
    }
}

private fun DrawScope.star(center: Offset, radius: Float, points: Int, color: Color) {
    val shape = Path()
    repeat(points * 2) { i ->
        val a = i * PI / points - PI / 2
        val r = if (i % 2 == 0) radius else radius * .36f
        val p = center + Offset(cos(a).toFloat() * r, sin(a).toFloat() * r)
        if (i == 0) shape.moveTo(p.x, p.y) else shape.lineTo(p.x, p.y)
    }
    shape.close()
    drawPath(shape, color)
}

@Composable
private fun rememberSelectionSound(): () -> Unit {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val pool = remember {
        SoundPool.Builder().setMaxStreams(1).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    }
    var soundId by remember { mutableIntStateOf(0) }
    DisposableEffect(pool, context, lifecycleOwner) {
        pool.setOnLoadCompleteListener { _, sample, status -> if (status == 0) soundId = sample }
        pool.load(context, R.raw.card_select, 1)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) pool.autoPause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            pool.release()
        }
    }
    return { if (soundId != 0) pool.play(soundId, .4f, .4f, 1, 0, 1f) }
}

