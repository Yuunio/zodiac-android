package com.example.witches.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.example.witches.core.Deck
import com.example.witches.data.MoveKind
import com.example.witches.data.MovePresentation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Layout coordinates only; identities are limited to cards already exposed by GameView. */
internal class MoveAnchors {
    var viewport: Rect = Rect.Zero
    val bounds = mutableMapOf<String, Rect>()

    fun visible(key: String, fallback: String): Rect {
        if (viewport.width < 1f || viewport.height < 1f) return Rect(0f, 0f, 1f, 1f)
        val rect = bounds[key] ?: bounds[fallback] ?: viewport
        val width = minOf(rect.width, viewport.width * .22f).coerceAtLeast(1f)
        val height = minOf(rect.height, width * 1.5f, viewport.height).coerceAtLeast(1f)
        val x = rect.center.x.coerceIn(viewport.left + width / 2, viewport.right - width / 2)
        val y = rect.center.y.coerceIn(viewport.top + height / 2, viewport.bottom - height / 2)
        return Rect(x - width / 2, y - height / 2, x + width / 2, y + height / 2)
    }
}

@Composable
internal fun MoveOverlay(
    presentation: MovePresentation, anchors: MoveAnchors, reducedMotion: Boolean,
    onComplete: (String, Long) -> Unit, modifier: Modifier = Modifier,
) {
    val progress = remember(presentation.key) { Animatable(0f) }
    val completed = remember(presentation.key) { booleanArrayOf(false) }
    val latestComplete = rememberUpdatedState(onComplete)
    fun complete() {
        if (!completed[0]) {
            completed[0] = true
            latestComplete.value(presentation.after.matchId, presentation.after.revision)
        }
    }
    DisposableEffect(presentation.key) { onDispose { complete() } }
    LaunchedEffect(presentation.key, reducedMotion) {
        if (!reducedMotion) {
            androidx.compose.runtime.withFrameNanos { }
            progress.animateTo(1f, tween(presentation.kind.durationMillis, easing = LinearOutSlowInEasing))
        }
        complete()
    }
    if (reducedMotion) return
    val density = LocalDensity.current
    val game = presentation.after
    val sourceKey = when {
        presentation.fromDeck -> "deck"
        presentation.actor == 1 -> "opponent"
        else -> "hand:${presentation.cardId}"
    }
    val source = { anchors.visible(sourceKey, if (presentation.actor == 0 && !presentation.fromDeck) "hand" else "deck") }
    val target = { anchors.visible("slot:${presentation.slot}", "deck") }
    val pool = { anchors.visible("pool:${presentation.actor}", "opponent") }
    Box(modifier.fillMaxSize()) {
        when (presentation.kind) {
            MoveKind.DEAL -> {
                val table = game.board.withIndex().filter { it.value != null }
                repeat(8) { index ->
                    val entries = listOf(
                        table.getOrNull(index)?.let { it.value to "slot:${it.index}" },
                        game.hand.getOrNull(index)?.let { it to "hand:$it" },
                        null to "opponent",
                    )
                    entries.forEachIndexed { side, entry -> if (entry != null) {
                        val order = index * 3 + side
                        FlyingCard(entry.first, order, density.density, anchors, {
                            val p = (progress.value * 1.6f - order * .6f / 23f).coerceIn(0f, 1f)
                            val from = anchors.visible("deck", "hand")
                            val to = anchors.visible(entry.second, if (side == 1) "hand" else "opponent")
                            CardFrame(between(from, to, p), if (p == 0f) 0f else 1f, rotation = (1f - p) * 8f)
                        }, back = { side == 2 })
                    } }
                }
            }
            MoveKind.PLACE -> FlyingCard(presentation.cardId, 0, density.density, anchors, {
                val p = progress.value
                val flip = presentation.actor == 1 && !presentation.fromDeck
                CardFrame(between(source(), target(), p), rotationY = if (flip) flipAngle(p) else 0f)
            }, back = { presentation.actor == 1 && !presentation.fromDeck && progress.value < .5f })
            MoveKind.CAPTURE -> {
                FlyingCard(presentation.cardId, 0, density.density, anchors, {
                    val p = progress.value
                    val gather = (p * 2f).coerceIn(0f, 1f)
                    val fly = ((p - .5f) * 2f).coerceIn(0f, 1f)
                    val destination = shrink(pool(), .3f)
                    CardFrame(if (p < .5f) between(source(), target(), gather) else between(target(), destination, fly),
                        alpha = 1f - fly * .85f, rotation = fly * -12f,
                        rotationY = if (presentation.actor == 1 && !presentation.fromDeck && p < .5f) flipAngle(gather) else 0f)
                }, back = { presentation.actor == 1 && !presentation.fromDeck && progress.value < .25f })
                FlyingCard(presentation.pairedCardId, 1, density.density, anchors, {
                    val fly = ((progress.value - .5f) * 2f).coerceIn(0f, 1f)
                    CardFrame(between(target(), shrink(pool(), .3f), fly), alpha = 1f - fly * .85f, rotation = fly * 12f,
                        shiftX = sin(fly * PI).toFloat() * 12f * density.density)
                })
            }
            MoveKind.DRAW -> FlyingCard(presentation.cardId, 0, density.density, anchors, {
                CardFrame(anchors.visible("deck", "opponent"), rotationY = flipAngle(progress.value))
            }, back = { progress.value < .5f })
            MoveKind.ANNOUNCE, MoveKind.TURN -> Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
                val p = progress.value
                val rect = anchors.visible(if (presentation.kind == MoveKind.ANNOUNCE) "score" else if (game.actor == 1) "opponent" else "hand", "deck")
                val center = rect.center - anchors.viewport.topLeft
                val radius = (16f + p * 36f) * density.density
                drawCircle(Gold.copy(alpha = (1f - p) * .5f), radius, center, style = Stroke(2.dp.toPx()))
                if (presentation.kind == MoveKind.ANNOUNCE) repeat(10) { index ->
                    val angle = index * PI / 5
                    val point = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * radius
                    drawCircle(Gold.copy(alpha = 1f - p), 2.dp.toPx(), point)
                }
            }
        }
        if (presentation.kind == MoveKind.ANNOUNCE && game.lastAward > 0) Text("+${game.lastAward}", color = Gold,
            fontFamily = Serif, fontSize = 28.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.graphicsLayer {
                val rect = anchors.visible("score", "deck")
                translationX = rect.center.x - anchors.viewport.left - size.width / 2f
                translationY = rect.center.y - anchors.viewport.top - progress.value * 24f * density.density
                alpha = 1f - progress.value
            }.clearAndSetSemantics { })
    }
}

private data class CardFrame(val rect: Rect, val alpha: Float = 1f, val rotation: Float = 0f, val rotationY: Float = 0f, val shiftX: Float = 0f)

@Composable
private fun FlyingCard(
    cardId: Int?, index: Int, density: Float, anchors: MoveAnchors, frame: () -> CardFrame,
    back: () -> Boolean = { false },
) {
    val initial = anchors.bounds["deck"]
    val width = ((initial?.width ?: 180f) / density).coerceIn(40f, 100f).dp
    val height = width * 1.45f
    Canvas(Modifier.requiredSize(width, height).graphicsLayer {
        val value = frame()
        translationX = value.rect.center.x - anchors.viewport.left - size.width / 2f + value.shiftX
        translationY = value.rect.center.y - anchors.viewport.top - size.height / 2f
        scaleX = value.rect.width / size.width
        scaleY = value.rect.height / size.height
        rotationZ = value.rotation
        rotationY = value.rotationY
        cameraDistance = 16f * density
        alpha = value.alpha
    }.clearAndSetSemantics { testTag = "move-card-$index" }) {
        val type = cardId?.let { Deck.card(it).type }
        val isBack = back() || type == null
        val tint = if (isBack) Panel else cardTint(requireNotNull(type).group)
        drawRoundRect(Brush.verticalGradient(listOf(tint, Night)), cornerRadius = CornerRadius(8.dp.toPx()))
        drawRoundRect(Gold, cornerRadius = CornerRadius(8.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        if (isBack) {
            drawCircle(Gold.copy(alpha = .6f), size.minDimension * .28f, style = Stroke(1.dp.toPx()))
            val r = size.minDimension * .14f
            drawLine(Gold, center - Offset(r, 0f), center + Offset(r, 0f), 3.dp.toPx())
            drawLine(Gold, center - Offset(0f, r), center + Offset(0f, r), 3.dp.toPx())
        } else drawZodiacSymbol(requireNotNull(type).sign, Gold)
    }
}

private fun between(from: Rect, to: Rect, progress: Float): Rect = Rect(
    from.left + (to.left - from.left) * progress, from.top + (to.top - from.top) * progress,
    from.right + (to.right - from.right) * progress, from.bottom + (to.bottom - from.bottom) * progress,
)

private fun shrink(rect: Rect, scale: Float): Rect = Rect(
    rect.center.x - rect.width * scale / 2f, rect.center.y - rect.height * scale / 2f,
    rect.center.x + rect.width * scale / 2f, rect.center.y + rect.height * scale / 2f,
)

private fun flipAngle(progress: Float): Float = if (progress < .5f) progress * 180f else (progress - 1f) * 180f
