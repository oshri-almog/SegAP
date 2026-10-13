package com.oshri.multihotspot.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
        // Draw beside the camera hole too (otherwise the system blacks out that strip); AppRoot pads content clear of it.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()
        setContent { MultiHotspotTheme { AppRoot() } }
    }

    // Dialogs, the keyboard or a swipe can bring the bars back; hide them again when we regain focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Full screen: status and navigation bars hidden; a swipe from the edge shows them briefly. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

enum class Tab(val label: String, val title: String, val icon: ImageVector) {
    STATUS("Status", "SegAP", Icons.Default.WifiTethering),
    HOTSPOT("Hotspot", "Hotspot settings", Icons.Default.Settings),
    NETWORKS("Networks", "Networks", Icons.Default.Lan),
    FIXED("Fixed IPs", "Fixed IPs & blocked devices", Icons.Default.PushPin),
    LOGS("Logs", "Logs", Icons.Default.Description),
}

/** One snackbar host for all tabs. */
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("no snackbar host") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableStateOf(Tab.STATUS) }
    // The network editor opens over the tabs (editingId null = new network).
    // Not saveable: the editor's fields aren't, so restoring it after the activity is recreated would show the saved
    // values instead of the edits. The tab is saved.
    var editing by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    val tabStates = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }

    fun openEditor(groupId: String?) { editingId = groupId; editing = true }

    BackHandler(enabled = editing || tab != Tab.STATUS) {
        if (editing) editing = false else tab = Tab.STATUS
    }

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

    // Full screen draws under the camera cutout; keep content clear of it.
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).windowInsetsPadding(WindowInsets.displayCutout),
    ) {
        CompositionLocalProvider(LocalSnackbar provides snackbar) {
            if (editing) GroupEditScreen(groupId = editingId, onDone = { editing = false })
            else Scaffold(
                topBar = {
                    Column {
                        TopAppBar(title = { Text(tab.title) })
                        // Edits now happen on several tabs; the Status card has its own Apply button.
                        if (tab != Tab.STATUS) PendingChangesBar()
                    }
                },
                bottomBar = {
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = t == tab,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label, maxLines = 1) },
                            )
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                // Each tab keeps its scroll position and unsaved edits while you visit the others.
                tabStates.SaveableStateProvider(tab.name) {
                    TabContent(tab, padding, onEditGroup = ::openEditor)
                }
            }
        }
    }
}

@Composable
private fun TabContent(tab: Tab, padding: PaddingValues, onEditGroup: (String?) -> Unit) {
    when (tab) {
        Tab.STATUS -> StatusTab(padding)
        Tab.HOTSPOT -> HotspotTab(padding)
        Tab.NETWORKS -> NetworksTab(padding, onEditGroup)
        Tab.FIXED -> FixedIpsTab(padding)
        Tab.LOGS -> LogsTab(padding)
    }
}
