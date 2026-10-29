package com.example.witches.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.witches.core.*
import com.example.witches.data.MoveKind
import com.example.witches.data.MovePresentation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun MatchScreen(
    game: GameView, busy: Boolean, onAction: (Action) -> Unit, onPause: () -> Unit,
    onMenu: () -> Unit, onNewGame: (Boolean) -> Unit, feedback: () -> Unit,
    computerMessage: String?, modifier: Modifier = Modifier, reducedMotion: Boolean = false,
    presentation: MovePresentation? = null, onPresentationComplete: (String, Long) -> Unit = { _, _ -> },
    matchTitle: String? = null, onNextLevel: (() -> Unit)? = null,
) {
    var selectedCard by rememberSaveable(game.matchId, game.round) { mutableStateOf<Int?>(null) }
    var acknowledgedResultKey by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by remember(game.matchId, game.round) { mutableStateOf<Int?>(null) }
    var poolSide by remember(game.matchId, game.round) { mutableStateOf<Int?>(null) }
    val anchors = remember(game.matchId) { MoveAnchors() }
    val pendingPresentation = presentation?.takeIf { it.after.matchId == game.matchId && it.after.revision == game.revision }
    val moving = pendingPresentation?.takeUnless { reducedMotion }
    val notified = remember(pendingPresentation?.key) { booleanArrayOf(false) }
    val finishPresentation: (String, Long) -> Unit = { id, revision ->
        if (pendingPresentation != null && pendingPresentation.after.matchId == id && pendingPresentation.after.revision == revision && !notified[0]) {
            notified[0] = true
            onPresentationComplete(id, revision)
        }
    }
    val latestPresentation by rememberUpdatedState(pendingPresentation)
    val latestComplete by rememberUpdatedState(finishPresentation)
    val pause = {
        pendingPresentation?.let { finishPresentation(it.after.matchId, it.after.revision) }
        onPause()
    }
    LaunchedEffect(game.revision) { if (selectedCard !in game.hand) selectedCard = null }
    val legal = GameEngine.legalActions(game)
    val tutorial = GameEngine.tutorialStep(game)
    val selectableCards = legal.filterIsInstance<Action.Play>().map { it.cardId }.toSet()
    val actorColor by animateColorAsState(if (game.actor == 1) Gold else Muted, tween(if (reducedMotion) 0 else 150), label = "activePlayer")
    val send: (Action) -> Unit = { if (!busy) { feedback(); onAction(it) } }
    val phaseText = tutorial?.instruction ?: when {
        game.actor == 1 && game.phase !in listOf(Phase.ROUND_END, Phase.MATCH_END, Phase.SESSION_END) -> "Ход Вилл…"
        game.phase == Phase.HAND -> if (selectedCard == null) "Выберите карту из руки" else "Выберите подсвеченную карту или пустое место"
        game.phase == Phase.DRAW -> "Вторая фаза: откройте карту колоды"
        game.phase == Phase.DRAW_TARGET -> "Найдите пару открытой карте или выберите пустое место"
        game.phase == Phase.DECLARE -> "Собрана новая комбинация"
        else -> "Розыгрыш завершён"
    }
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
    BoxWithConstraints(modifier.onGloballyPositioned { coordinates ->
        val previous = anchors.viewport
        anchors.viewport = coordinates.boundsInRoot()
        if (previous != Rect.Zero && previous.size != anchors.viewport.size) latestPresentation?.let {
            latestComplete(it.after.matchId, it.after.revision)
        }
    }) {
    val headerScale = if (maxWidth > maxHeight) fontScale.coerceAtMost(1.25f) else fontScale
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp * headerScale), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = pause, modifier = Modifier.heightIn(min = 48.dp)) { Text("Пауза") }
            Column(Modifier.weight(1f).fillMaxHeight().clipToBounds().verticalScroll(rememberScrollState())
                .onGloballyPositioned { anchors.bounds["score"] = it.boundsInRoot() }, horizontalAlignment = Alignment.End) {
                matchTitle?.let { Text(it, color = Muted, fontSize = 11.sp, lineHeight = 14.sp) }
                AnimatedContent(targetState = game.scores, transitionSpec = {
                    fadeIn(tween(if (reducedMotion) 0 else 250)) togetherWith fadeOut(tween(if (reducedMotion) 0 else 150))
                }, label = "matchScore") { scores ->
                    Text(if (game.training) "Учебная партия" else "Вы ${scores[0]}${game.target?.let { " / $it" } ?: ""} · Вилл ${scores[1]}", color = Gold, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp)
                }
                Text("Розыгрыш ${game.round} · В руке Вилл: ${game.opponentHandCount}", color = actorColor, fontSize = 11.sp, lineHeight = 14.sp,
                    modifier = Modifier.onGloballyPositioned { anchors.bounds["opponent"] = it.boundsInRoot() })
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth > 660.dp || maxWidth > maxHeight
            val handHeight = (maxHeight * if (wide) .4f else .25f).coerceIn(if (wide) 92.dp else 112.dp, 160.dp * fontScale)
            val poolHeight = (if (wide) 48.dp else 56.dp) * (fontScale / 1.5f).coerceAtLeast(1f)
            val instructionHeight = (maxHeight * .14f).coerceIn(56.dp, 112.dp * fontScale)
            val summary = game.target == null && game.phase == Phase.ROUND_END && moving == null
            val instruction = if (summary) {
                if (game.roundWinner == null) "Розыгрыш без объявления" else "${if (game.roundWinner == 0) "Вы" else "Вилл"}: +${game.lastAward} очков"
            } else phaseText
            val message = if (summary) "Следующая раздача начнётся автоматически" else computerMessage
            if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    MatchBoard(game, selectedCard, legal, busy, send, { detail = it }, anchors, moving, reducedMotion,
                        Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    MatchInstructions(instruction, message, tutorial?.restartRequired == true, busy, { onNewGame(true) }, Modifier.weight(1f))
                    PoolSummary(game, anchors, reducedMotion, { poolSide = it }, Modifier.height(poolHeight))
                    Hand(game, selectedCard, busy, selectableCards, tutorial?.expectedCardId,
                        { selectedCard = if (selectedCard == it) null else it; feedback() }, { detail = it }, anchors, moving, reducedMotion,
                        Modifier.height(handHeight))
                }
            } else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                MatchInstructions(instruction, message, tutorial?.restartRequired == true, busy, { onNewGame(true) },
                    Modifier.height(instructionHeight))
                MatchBoard(game, selectedCard, legal, busy, send, { detail = it }, anchors, moving, reducedMotion,
                    Modifier.weight(1f).fillMaxWidth())
                PoolSummary(game, anchors, reducedMotion, { poolSide = it }, Modifier.height(poolHeight))
                Hand(game, selectedCard, busy, selectableCards, tutorial?.expectedCardId,
                    { selectedCard = if (selectedCard == it) null else it; feedback() }, { detail = it }, anchors, moving, reducedMotion,
                    Modifier.height(handHeight))
            }
        }
    }
        pendingPresentation?.let { MoveOverlay(it, anchors, reducedMotion, finishPresentation, Modifier.matchParentSize()) }
    }
    detail?.let { id ->
        val card = Deck.card(id)
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(card.type.sign.label, fontFamily = Serif) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                ZodiacCard(id, false, false, {}, {}, Modifier.width(150.dp).height(205.dp))
                Text("Категория: ${card.type.group.label}." + (card.type.guardian?.let { " Чародейка: ${it.label}. Считается и в стихии, и среди чародеек." } ?: ""), Modifier.padding(top = 16.dp))
                Text("Для захвата выберите карту того же знака: ${card.type.sign.label}. Цвет и категория не ограничивают пару.", Modifier.padding(top = 12.dp))
            }
        }, confirmButton = { TextButton(onClick = { detail = null }) { Text("Закрыть") } })
    }
    poolSide?.let { side -> AlertDialog(onDismissRequest = { poolSide = null }, title = { Text(if (side == 0) "Ваши собранные карты" else "Собранные карты Вилл") }, text = {
        val captured = if (side == 0) game.ownCaptured else game.opponentCaptured
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Собранные карты обоих игроков открыты. Чародейка учитывается и в своей стихии, и среди чародеек.")
            val combinations = GameRules.combinations(captured)
            if (combinations.isEmpty()) Text("Пока нет комбинаций для объявления.")
            combinations.forEach { Text("${it.name}: ${it.points}") }
            Text("Сумма комбинаций: ${combinations.sumOf { it.points }}. Очки даёт объявление, а не отдельный захват.")
            Group.entries.forEach { group ->
                val cards = captured.filter { Deck.card(it).type.group == group }.sortedBy { Deck.card(it).type.sign.ordinal }
                HorizontalDivider()
                Text("${group.label}: ${cards.size}/${groupSize(group)}", fontWeight = FontWeight.SemiBold)
                if (cards.isEmpty()) Text("Пока нет карт", color = Muted)
                BoxWithConstraints {
                    val cardScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
                    val columns = maxOf(1, (maxWidth / (70.dp * cardScale)).toInt())
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        cards.chunked(columns).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { id -> ZodiacCard(id, false, false, {}, { poolSide = null; detail = id },
                                Modifier.width(64.dp * cardScale).height(100.dp * cardScale), if (side == 0) "Ваши собранные карты. " else "Собранные карты Вилл. ") }
                        } }
                    }
                }
            }
            Text("Чародейки: ${captured.count { Deck.card(it).type.guardian != null }}/5")
        }
    }, confirmButton = { TextButton(onClick = { poolSide = null }) { Text("Закрыть") } }) }
    if (game.phase == Phase.DECLARE && game.actor == 0 && moving == null) {
        val combinations = GameRules.combinations(game.ownCaptured)
        AlertDialog(onDismissRequest = {}, title = { Text("Объявить комбинации?") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                combinations.forEach { Text("${it.name}: ${it.points}") }
                Text("Итого: ${combinations.sumOf { it.points }} очков.", fontWeight = FontWeight.Bold)
                Text("Объявление завершит розыгрыш. Если продолжить, объявить собранное можно только после следующей новой комбинации. Вилл может закончить розыгрыш раньше вас.")
            }
        }, confirmButton = { TextButton(onClick = { send(Action.Announce) }, enabled = !busy) { Text("Объявить") } },
            dismissButton = { if (Action.Continue in legal) TextButton(onClick = { send(Action.Continue) }, enabled = !busy) { Text("Продолжить розыгрыш") } })
    }
    if ((game.phase == Phase.ROUND_END && game.target != null || game.phase == Phase.MATCH_END) && moving == null) {
        val finished = game.phase == Phase.MATCH_END
        val resultKey = "${game.matchId}:${game.revision}"
        if (game.roundWinner == 1 && acknowledgedResultKey != resultKey) {
            AlertDialog(onDismissRequest = {}, title = { Text("Вилл объявила комбинации") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameRules.combinations(game.opponentCaptured).forEach { Text("${it.name}: ${it.points}") }
                    Text("Сумма объявления: ${game.lastAward} очков.", fontWeight = FontWeight.Bold)
                }
            }, confirmButton = {
                TextButton(onClick = { acknowledgedResultKey = resultKey }, enabled = !busy) { Text("Показать итог") }
            })
        } else AlertDialog(onDismissRequest = {}, title = { Text(if (finished) { if (game.winner == 0) "Победа!" else "Победила Вилл" } else "Розыгрыш завершён") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (finished && game.winner == 0) VictoryStars(game.matchId, reducedMotion)
                Text(if (game.roundWinner == null) "Карты в обеих руках закончились. Без объявления очки не начисляются. Следующая раздача начнётся с вашего хода."
                    else "${if (game.roundWinner == 0) "Вы объявили" else "Вилл объявила"} комбинации: +${game.lastAward}.")
                if (game.roundWinner != null) {
                    val cards = if (game.roundWinner == 0) game.ownCaptured else game.opponentCaptured
                    GameRules.combinations(cards).forEach { Text("${it.name}: ${it.points}") }
                }
                Text("Счёт: ${game.scores[0]} — ${game.scores[1]}.")
                if (finished) Text(if (game.training) "Учебная партия завершена." else "Достигнут порог ${game.target} очков.")
            }
        }, confirmButton = { TextButton(onClick = {
            if (finished && game.winner == 0 && onNextLevel != null) onNextLevel()
            else if (finished) onNewGame(false) else send(Action.NextRound)
        }, enabled = !busy) { Text(if (finished && game.winner == 0 && onNextLevel != null) "Следующий уровень" else if (finished) "Новая игра" else "Следующий розыгрыш") } },
            dismissButton = { TextButton(onClick = onMenu) { Text("В меню") } })
    }
    if (game.phase == Phase.SESSION_END) AlertDialog(onDismissRequest = {}, title = { Text("Бесконечная игра завершена") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Счёт: ${game.scores[0]} — ${game.scores[1]}.")
            Text("Розыгрышей: ${game.round}. Победитель в этом режиме не определяется.")
        }
    }, confirmButton = { TextButton(onClick = { onNewGame(false) }, enabled = !busy) { Text("Новая игра") } },
        dismissButton = { TextButton(onClick = onMenu) { Text("В меню") } })
}

@Composable
private fun MatchInstructions(text: String, computerMessage: String?, restart: Boolean, busy: Boolean, onRestart: () -> Unit,
    modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(text, computerMessage, restart) { scroll.scrollTo(0) }
    Column(modifier.fillMaxWidth().testTag("match-instructions").clipToBounds().verticalScroll(scroll).padding(vertical = 4.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(bottom = 8.dp), color = Ink, fontSize = 14.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
        computerMessage?.let { Text(it, Modifier.fillMaxWidth().padding(bottom = 6.dp), color = Gold, fontSize = 12.sp, lineHeight = 16.sp) }
        if (restart) Button(onClick = onRestart, enabled = !busy) { Text("Начать обучение заново") }
    }
}

@Composable
private fun PoolSummary(game: GameView, anchors: MoveAnchors, reducedMotion: Boolean, onClick: (Int) -> Unit,
    modifier: Modifier = Modifier) {
    val showDetailsLabel = LocalDensity.current.fontScale < 1.3f
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(game.ownCaptured, game.opponentCaptured).forEachIndexed { side, cards ->
            val glow = remember(game.matchId, side) { Animatable(0f) }
            LaunchedEffect(cards.size, reducedMotion) {
                if (!reducedMotion && cards.isNotEmpty()) { glow.snapTo(1f); glow.animateTo(0f, tween(550)) }
                else glow.snapTo(0f)
            }
            val count by animateIntAsState(cards.size, tween(if (reducedMotion) 0 else 300), label = "poolCount$side")
            Surface(onClick = { onClick(side) }, modifier = Modifier.weight(1f).fillMaxHeight()
                .onGloballyPositioned { anchors.bounds["pool:$side"] = it.boundsInRoot() }
                .drawWithContent { drawContent(); drawRoundRect(Gold.copy(alpha = glow.value * .65f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) },
                shape = RoundedCornerShape(10.dp), color = Panel) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalArrangement = Arrangement.Center) {
                    Text(if (side == 0) "Ваши наборы · $count" else "Наборы Вилл · $count", color = Gold, fontSize = 12.sp,
                        lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    if (showDetailsLabel) Text(if (game.phase == Phase.DECLARE && game.actor == side) "Новая комбинация" else "Посмотреть карты",
                        color = Gold, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun groupSize(group: Group): Int = when (group) {
    Group.SIMPLE -> 24
    Group.TRIANGLE -> 3
    Group.SQUARE -> 4
    Group.PENTAGON -> 5
    else -> 3
}

@Composable
private fun Hand(game: GameView, selected: Int?, busy: Boolean, selectable: Set<Int>, expected: Int?, onSelect: (Int) -> Unit, onDetail: (Int) -> Unit,
    anchors: MoveAnchors, moving: MovePresentation?, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val cardScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
    Column(modifier.fillMaxWidth().testTag("match-hand")) {
        Text("ВАША РУКА · ${game.hand.size}", color = Muted, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyRow(Modifier.fillMaxSize().onGloballyPositioned { anchors.bounds["hand"] = it.boundsInRoot() }, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 7.dp, bottom = 6.dp)) {
                items(game.hand, key = { it }) { id ->
                    ZodiacCard(id, selected == id, !busy && id in selectable,
                        { onSelect(id) }, { if (!busy) onDetail(id) }, Modifier.width(70.dp * cardScale).fillParentMaxHeight()
                            .onGloballyPositioned { anchors.bounds["hand:$id"] = it.boundsInRoot() }
                            .graphicsLayer { alpha = if (moving?.kind == MoveKind.DEAL) 0f else 1f }
                            .then(if (id == expected) Modifier.border(3.dp, Ink, RoundedCornerShape(8.dp)) else Modifier), "Рука. ", reducedMotion, selectedLift = selected == id)
                }
            }
            if (game.hand.isEmpty()) Text("Рука пуста", color = Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun MatchBoard(game: GameView, selected: Int?, legal: List<Action>, busy: Boolean, send: (Action) -> Unit, onDetail: (Int) -> Unit,
    anchors: MoveAnchors, moving: MovePresentation?, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val cardRatio = .72f / LocalDensity.current.fontScale.coerceIn(1f, 2f)
    val rows = listOf(listOf(10, 0, 1, 2, 13), listOf(11, 3, 4, 5, 12), listOf(9, 6, 7, 8, 14)) +
        (15 until maxOf(game.board.size, (legal.mapNotNull { when (it) { is Action.Play -> it.slot; is Action.ResolveDraw -> it.slot; else -> null } }.maxOrNull() ?: 14) + 1)).chunked(5)
    BoxWithConstraints(modifier.testTag("match-board"), contentAlignment = Alignment.Center) {
        val boardWidth = minOf(maxWidth, 560.dp)
        val cellHeight = minOf((boardWidth - 20.dp) / 5 / cardRatio, (maxHeight - 5.dp * (rows.size - 1)) / rows.size).coerceAtLeast(1.dp)
        Column(Modifier.width(boardWidth), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            rows.forEachIndexed { row, slots ->
                Row(Modifier.height(cellHeight), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    slots.forEachIndexed { col, slot ->
                        val cell = Modifier.weight(1f).fillMaxHeight().onGloballyPositioned { anchors.bounds["slot:$slot"] = it.boundsInRoot(); if (slot == 4) anchors.bounds["deck"] = it.boundsInRoot() }
                        if (slot == 4) {
                            val drawn = game.drawnCard
                            if (drawn != null) ZodiacCard(drawn, true, false, {}, { if (!busy) onDetail(drawn) }, cell.graphicsLayer { alpha = if (moving?.kind == MoveKind.DRAW) 0f else 1f }, "Открытая карта. ", reducedMotion)
                            else Surface(onClick = { send(Action.Draw) }, enabled = !busy && Action.Draw in legal, modifier = cell.semantics { contentDescription = "Колода. Осталось ${game.deckCount} карт" },
                                shape = RoundedCornerShape(8.dp), color = Panel, border = androidx.compose.foundation.BorderStroke(1.dp, if (Action.Draw in legal) Gold else Line)) {
                                Column(Modifier.padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text("${game.deckCount}", color = Gold, fontFamily = Serif, fontSize = 18.sp, lineHeight = 20.sp)
                                    Text("КОЛОДА", color = Muted, fontSize = 8.sp, lineHeight = 10.sp, maxLines = 1)
                                }
                            }
                        } else {
                            val id = game.board.getOrNull(slot)
                            val action = legal.firstOrNull { when (it) {
                                is Action.Play -> it.cardId == selected && it.slot == slot
                                is Action.ResolveDraw -> it.slot == slot
                                else -> false
                            } }
                            if (id != null) ZodiacCard(id, action != null, !busy && action != null, { action?.let(send) }, { if (!busy) onDetail(id) },
                                cell.graphicsLayer { alpha = if (moving?.kind == MoveKind.DEAL || moving?.kind == MoveKind.PLACE && moving.slot == slot) 0f else 1f },
                                "Стол, ряд ${row + 1}, столбец ${col + 1}. ", reducedMotion)
                            else Surface(onClick = { action?.let(send) }, enabled = !busy && action != null, modifier = cell.semantics { contentDescription = "Пустая клетка, ряд ${row + 1}, столбец ${col + 1}" },
                                shape = RoundedCornerShape(8.dp), color = Night.copy(alpha = .3f), border = androidx.compose.foundation.BorderStroke(1.dp, if (action != null) Gold else Line.copy(alpha = .4f))) {
                                Box(contentAlignment = Alignment.Center) { if (action != null) Text("+", color = Gold, fontSize = 24.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ZodiacCard(id: Int, highlighted: Boolean, enabled: Boolean, onClick: () -> Unit, onDetail: () -> Unit, modifier: Modifier = Modifier,
    prefix: String = "", reducedMotion: Boolean = false, selectedLift: Boolean = false) {
    val card = Deck.card(id).type
    val tint = cardTint(card.group)
    val lift by animateFloatAsState(if (selectedLift) 1f else 0f, tween(if (reducedMotion) 0 else 150), label = "cardLift$id")
    val emphasis by animateFloatAsState(if (highlighted) 1f else 0f, tween(if (reducedMotion) 0 else 150), label = "cardEmphasis$id")
    BoxWithConstraints(modifier.graphicsLayer { translationY = -6.dp.toPx() * lift; scaleX = 1f + .035f * lift; scaleY = 1f + .035f * lift }
        .clip(RoundedCornerShape(8.dp)).background(Brush.verticalGradient(listOf(tint, Night)))
        .drawWithContent { drawContent(); drawRoundRect(androidx.compose.ui.graphics.lerp(Line, Gold, emphasis), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke((1f + emphasis).dp.toPx())) }
        .combinedClickable(role = Role.Button, onClickLabel = if (enabled) "Выбрать карту или цель" else "Подробности карты",
            onClick = { if (enabled) onClick() else onDetail() }, onLongClick = onDetail, onLongClickLabel = "Подробности карты")
        .semantics { contentDescription = prefix + card.sign.label + ". " + card.group.label + (card.guardian?.let { ". ${it.label}" } ?: ""); this.selected = highlighted }) {
        val compact = maxHeight < 90.dp * LocalDensity.current.fontScale
        Column(Modifier.fillMaxSize().padding(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(card.sign.label, color = Ink, fontSize = 10.sp, lineHeight = 11.sp, maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Canvas(Modifier.weight(1f).fillMaxWidth()) { drawZodiacSymbol(card.sign, Gold) }
            Text(card.group.label + (card.guardian?.let { "\n${it.label}" } ?: ""), color = Gold,
                fontSize = 8.sp, lineHeight = 9.sp, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

internal fun cardTint(group: Group): Color = when (group) {
        Group.SIMPLE -> Color(0xFF375B57)
        Group.TRIANGLE -> Color(0xFF965328)
        Group.SQUARE -> Color(0xFF8A4564)
        Group.PENTAGON -> Color(0xFF406F48)
        Group.FIRE -> Color(0xFF883B48)
        Group.EARTH -> Color(0xFF82652D)
        Group.WATER -> Color(0xFF345D92)
        Group.AIR -> Color(0xFF624C90)
}

@Composable
private fun VictoryStars(matchId: String, reducedMotion: Boolean) {
    val progress = remember(matchId) { Animatable(0f) }
    LaunchedEffect(matchId, reducedMotion) { if (!reducedMotion) progress.animateTo(1f, tween(1100)) }
    if (!reducedMotion) Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val p = progress.value
        repeat(18) { index ->
            val angle = index * PI / 9
            val point = center + Offset(cos(angle).toFloat() * size.width * .43f, sin(angle).toFloat() * size.height * .4f) * p
            drawCircle(Gold.copy(alpha = 1f - p * .8f), (1f + sin(PI * p).toFloat() * 2f).dp.toPx(), point)
        }
    }
}
