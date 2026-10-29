package com.example.witches

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.witches.data.AppViewModel
import com.example.witches.ui.ZodiacApp

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            ZodiacApp(
                state = model.state.collectAsStateWithLifecycle().value,
                onSettingsChange = model::updateSettings,
                onSelectCard = model::selectCard,
                onDismissMessage = model::dismissMessage,
                onNewGame = model::startGame,
                onAction = model::submit,
                onResumeComputer = model::resumeComputerTurn,
                onPauseComputer = model::pauseComputer,
                onStartSession = model::startSession,
                onSelectSession = model::selectSession,
                onPresentationComplete = model::completePresentation,
            )
        }
    }
}
