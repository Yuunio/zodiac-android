package com.example.witches.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.witches.core.Deck

@Composable
internal fun CardCatalog(onBack: () -> Unit, feedback: () -> Unit, modifier: Modifier = Modifier, reducedMotion: Boolean = false) {
    val cards = remember { Deck.cards.distinctBy { it.type.key } }
    var selectedId by rememberSaveable { mutableStateOf<Int?>(null) }
    var detailId by rememberSaveable { mutableStateOf<Int?>(null) }
    val cardWidth = 96.dp * LocalDensity.current.fontScale.coerceIn(1f, 2f)
    val openDetail: (Int) -> Unit = { feedback(); detailId = it }
    BackHandler(detailId != null) { detailId = null }

    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Назад", color = Muted) }
            Text("Карты Зодиака", Modifier.weight(1f).padding(start = 8.dp).semantics { heading() },
                color = Gold, fontFamily = Serif, fontSize = 24.sp)
        }
        HorizontalDivider(color = Line)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(cardWidth),
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("card_catalog_grid"),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${cards.size} типов · ${Deck.cards.size} карт в колоде", color = Muted, fontSize = 13.sp)
                    Text("Коснитесь карты, чтобы выбрать её. Удерживайте для подробностей.", color = Muted, fontSize = 13.sp)
                    selectedId?.let { id ->
                        val card = Deck.card(id).type
                        Text("Выбрана карта: ${card.sign.label} · ${card.group.label}", Modifier.fillMaxWidth(),
                            color = Gold, fontSize = 14.sp, textAlign = TextAlign.Center)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { selectedId?.let(openDetail) }, enabled = selectedId != null,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Подробности карты") }
                        TextButton(onClick = { selectedId = null }, enabled = selectedId != null,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Снять выбор") }
                    }
                }
            }
            items(cards, key = { it.type.key }) { card ->
                ZodiacCard(card.id, selectedId == card.id, true,
                    { feedback(); selectedId = if (selectedId == card.id) null else card.id },
                    { openDetail(card.id) }, Modifier.fillMaxWidth().aspectRatio(.7f), "Каталог. ", reducedMotion = reducedMotion)
            }
        }
    }
    detailId?.let { id ->
        val card = Deck.card(id).type
        AlertDialog(
            onDismissRequest = { detailId = null },
            title = { Text(card.sign.label, color = Gold, fontFamily = Serif) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ZodiacCard(id, false, false, {}, {}, Modifier.width(150.dp).height(215.dp), "Подробности. ", reducedMotion = reducedMotion)
                    Text("Категория: ${card.group.label}.")
                    card.guardian?.let { Text("Чародейка: ${it.label}. Эта карта также учитывается среди чародеек.") }
                    Text("Для захвата важен знак: ${card.sign.label}. Цвет и категория не ограничивают пару.")
                }
            },
            confirmButton = { TextButton(onClick = { detailId = null }) { Text("Закрыть") } },
        )
    }
}
