package com.oshri.multihotspot.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.oshri.multihotspot.HotspotController
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HotspotController.init(this)
        enableEdgeToEdge()
        setContent { MultiHotspotTheme { AppRoot() } }
    }
}

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object Log : Screen
    /** [groupId] null = new network. */
    data class EditGroup(val groupId: String?) : Screen
}

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    // Poll engine status while the app is visible.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            HotspotController.checkRoot()
            while (true) {
                HotspotController.refresh()
                delay(2_000)
            }
        }
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            onSettings = { screen = Screen.Settings },
            onLog = { screen = Screen.Log },
            onEditGroup = { screen = Screen.EditGroup(it) },
        )
        Screen.Settings -> SettingsScreen(onDone = { screen = Screen.Home })
        Screen.Log -> LogScreen(onDone = { screen = Screen.Home })
        is Screen.EditGroup -> GroupEditScreen(groupId = s.groupId, onDone = { screen = Screen.Home })
    }
}
