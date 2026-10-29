package com.example.witches.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.witches.core.Difficulty
import com.example.witches.core.Phase
import com.example.witches.data.*

internal fun SessionView.isOngoing() = game.phase !in listOf(Phase.MATCH_END, Phase.SESSION_END)

@Composable
internal fun CampaignScreen(
    state: PrototypeUiState, onStart: (SessionConfig) -> Unit, onResume: () -> Unit,
    onBack: () -> Unit, modifier: Modifier = Modifier,
) {
    ModePage("Прохождение", onBack, modifier) {
        Text("10 уровней · 30 испытаний", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("Победа на лёгкой открывает следующий уровень и среднюю сложность этого уровня. Победа на средней открывает сложную.", color = Muted)
        if (state.sessions[SessionSlot.CAMPAIGN]?.isOngoing() == true) {
            Button(onClick = onResume, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Продолжить прохождение")
            }
        }
        (1..10).forEach { level ->
            Surface(shape = RoundedCornerShape(16.dp), color = Panel,
                border = BorderStroke(1.dp, Line.copy(alpha = .6f)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Уровень $level", color = Ink, fontSize = 20.sp, modifier = Modifier.semantics { heading() })
                    Text("Цель: ${level * 100} очков", color = Gold)
                    Difficulty.entries.forEach { difficulty ->
                        val unlocked = state.progress.isUnlocked(level, difficulty)
                        val done = state.progress.isCompleted(level, difficulty)
                        val status = when { done -> "Пройдено"; unlocked -> "Доступно"; else -> "Закрыто" }
                        OutlinedButton(onClick = { onStart(SessionConfig(SessionSlot.CAMPAIGN, difficulty, level)) },
                            enabled = unlocked && !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                                contentDescription = "Уровень $level. ${difficulty.label}. $status"
                            }) { Text("${difficulty.label} · $status") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EndlessScreen(
    state: PrototypeUiState, onStart: (SessionConfig) -> Unit, onResume: () -> Unit,
    onBack: () -> Unit, modifier: Modifier = Modifier,
) {
    var selected by rememberSaveable { mutableStateOf(Difficulty.NORMAL.name) }
    ModePage("Бесконечная игра", onBack, modifier) {
        if (!state.progress.endlessUnlocked) {
            Text("Режим пока закрыт", color = Gold, fontSize = 22.sp)
            Text("Пройдите все 10 уровней на лёгкой сложности, чтобы открыть бесконечную игру.", color = Ink)
            Text("Пройдено ${state.progress.completed.count { it and 1 != 0 }} из 10", color = Muted)
        } else {
            Text("Игра без предела очков", color = Gold, fontSize = 22.sp)
            Text("Розыгрыши сменяются автоматически. Выход сохраняет сессию, а завершить её можно через паузу. Прохождение хранится отдельно.", color = Muted)
            if (state.sessions[SessionSlot.ENDLESS]?.isOngoing() == true) {
                Button(onClick = onResume, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Продолжить бесконечную игру")
                }
            }
            Text("Сложность Вилл", color = Ink, fontWeight = FontWeight.SemiBold)
            Difficulty.entries.forEach { difficulty ->
                OutlinedButton(onClick = { selected = difficulty.name }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                        contentDescription = "Бесконечная игра. ${difficulty.label}"
                        this.selected = selected == difficulty.name
                    }, border = BorderStroke(if (selected == difficulty.name) 2.dp else 1.dp,
                        if (selected == difficulty.name) Gold else Line)) {
                    Text((if (selected == difficulty.name) "✓ " else "") + difficulty.label)
                }
            }
            Text("Сложность выбирается до начала и сохраняется до завершения сессии.", color = Muted)
            Button(onClick = { onStart(SessionConfig(SessionSlot.ENDLESS, Difficulty.valueOf(selected))) },
                enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                Text("Начать бесконечную игру")
            }
        }
    }
}

@Composable
private fun ModePage(title: String, onBack: () -> Unit, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Назад") }
            Text(title, Modifier.padding(start = 12.dp).semantics { heading() }, fontFamily = Serif, color = Gold, fontSize = 25.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).widthIn(max = 620.dp)
            .fillMaxWidth().align(Alignment.CenterHorizontally).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}
