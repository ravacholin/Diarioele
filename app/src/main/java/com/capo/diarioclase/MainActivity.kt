package com.capo.diarioclase

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.capo.diarioclase.ui.archive.*
import com.capo.diarioclase.ui.capture.*

sealed interface AppScreen {
    data object Capture : AppScreen
    data object Archive : AppScreen
    data object Settings : AppScreen
}

private val screenSaver = Saver<AppScreen, String>(
    save = { when (it) { AppScreen.Capture -> "capture"; AppScreen.Archive -> "archive"; AppScreen.Settings -> "settings" } },
    restore = { when (it) { "capture" -> AppScreen.Capture; "settings" -> AppScreen.Settings; else -> AppScreen.Archive } },
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(permissions.toTypedArray())
        val app = application as DiarioClaseApp
        val provider = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                CaptureViewModel::class.java -> CaptureViewModel(
                    app.repository, AndroidCaptureActions(app, app),
                    drafts = app.processingStore.observeLatestDraft(), claims = app.processingStore.observeLatestClaims(),
                    diaries = app.diaryRepository.observeEntries(""),
                ) as T
                ArchiveViewModel::class.java -> ArchiveViewModel(app.diaryRepository) as T
                else -> error("Modelo de pantalla desconocido")
            }
        })
        val capture = provider[CaptureViewModel::class.java]
        val archive = provider[ArchiveViewModel::class.java]
        setContent {
            var screen by rememberSaveable(stateSaver = screenSaver) { mutableStateOf<AppScreen>(AppScreen.Archive) }
            val captureState by capture.state.collectAsState()
            val archiveState by archive.state.collectAsState()
            LaunchedEffect(captureState.status, captureState.diaryId) {
                if (captureState.status == CaptureStatus.CLEANUP_PENDING) screen = AppScreen.Capture
                if (captureState.status == CaptureStatus.ARCHIVED) captureState.diaryId?.let { id ->
                    archive.openSavedDiary(id)
                    screen = AppScreen.Archive
                }
            }
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color.White, onPrimary = Color.Black, secondary = Color.LightGray,
                    onSecondary = Color.Black, tertiary = Color.LightGray, background = Color.Black,
                    surface = Color.Black, onBackground = Color.White, onSurface = Color.White,
                    onSurfaceVariant = Color.LightGray, surfaceVariant = Color.DarkGray,
                    outline = Color.Gray, error = Color.White, onError = Color.Black,
                ),
                shapes = Shapes(RoundedCornerShape(0.dp), RoundedCornerShape(0.dp), RoundedCornerShape(0.dp), RoundedCornerShape(0.dp), RoundedCornerShape(0.dp)),
            ) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = Color.Black) {
                    when (screen) {
                        AppScreen.Archive -> ArchiveScreen(archiveState, archive,
                            onCapture = { screen = AppScreen.Capture }, onSettings = { screen = AppScreen.Settings })
                        AppScreen.Settings -> SettingsScreen(archiveState, archive::setMode, onArchive = { screen = AppScreen.Archive })
                        AppScreen.Capture -> {
                            BackHandler { screen = AppScreen.Archive }
                            Column(Modifier.fillMaxSize().background(Color.Black)) {
                                TextButton(onClick = { screen = AppScreen.Archive }, modifier = Modifier.padding(horizontal = 12.dp), shape = RectangleShape) {
                                    Text("DIARIOS GUARDADOS")
                                }
                                Box(Modifier.weight(1f)) {
                                    CaptureScreen(
                                        captureState, capture::onStart, capture::onPause, capture::onResume,
                                        capture::onMarkHomework, capture::onFinalize, capture::onProcess,
                                        capture::onMode, capture::onRequestModel, capture::onSaveDraft,
                                        capture::onApprove, capture::onRetryCleanup,
                                        interpretationMode = archiveState.mode,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
