package com.oshri.multihotspot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oshri.multihotspot.HotspotController
import com.oshri.multihotspot.engine.BackendKind
import com.oshri.multihotspot.engine.Capabilities
import com.oshri.multihotspot.engine.EngineMode
import com.oshri.multihotspot.engine.ShizukuSupport

const val NEEDS_ROOT = "Needs root - the Shizuku engine runs the phone's own hotspot"
const val PHONE_CANT = "This phone's hotspot doesn't support this"

/** What the engine in use can do (everything while no engine is found: the setup card explains). */
@Composable
fun capabilities(): Capabilities = HotspotController.capabilities.collectAsStateWithLifecycle().value

/** Why a capability is missing: root-only, or (with Shizuku) the phone's hotspot can't. */
fun phoneReason(rootHas: Boolean): String = if (rootHas) PHONE_CANT else NEEDS_ROOT

/**
 * Greys out something the engine in use can't do and ignores taps (and accessibility actions) on it. Only presses
 * are swallowed, before the controls see them: drags still reach the scrolling list around it.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.unavailable(reason: String?): Modifier = if (reason == null) this else this
    .alpha(0.38f)
    .clearAndSetSemantics { contentDescription = reason; disabled() }
    // Tab / the keyboard's Next key mustn't move into it either.
    .focusProperties { enter = { FocusRequester.Cancel } }
    .focusGroup()
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach {
                if (it.changedToDownIgnoreConsumed() || it.changedToUpIgnoreConsumed()) it.consume()
            }
        }
    }

/** [content] as usual when [available]; otherwise greyed out under a line saying why. */
@Composable
fun Gated(available: Boolean, reason: String = NEEDS_ROOT, content: @Composable ColumnScope.() -> Unit) {
    if (available) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        UnavailableNote(reason)
        Column(Modifier.unavailable(reason), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun UnavailableNote(reason: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            reason,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** Status tab, while neither root nor Shizuku can be used: what SegAP needs and the next step. */
@Composable
fun EngineSetupCard() {
    val mode by HotspotController.engineMode.collectAsStateWithLifecycle()
    val shizuku by HotspotController.shizukuState.collectAsStateWithLifecycle()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when (mode) {
                    EngineMode.ROOT -> "No root access"
                    EngineMode.SHIZUKU -> "Shizuku isn't ready"
                    EngineMode.AUTO -> "SegAP needs root or Shizuku"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            if (mode != EngineMode.SHIZUKU) Text(
                "Root (Magisk): every feature. On a rooted phone, allow root for SegAP in Magisk. (Some phones only " +
                    "load Magisk when booted through recovery: `adb reboot recovery` or the Magisk key combo.)",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (mode != EngineMode.ROOT) {
                Text(
                    "Shizuku (no root): SegAP runs the phone's own hotspot with your first network's password. " +
                        "More networks, fixed IPs, the firewall and Tailscale need root.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ShizukuStep(shizuku)
            } else if (shizuku == ShizukuSupport.State.READY) {
                Text("Shizuku is ready, but the engine is set to Root only.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { HotspotController.setEngineMode(EngineMode.AUTO) }) { Text("Use Shizuku when there's no root") }
            }
        }
    }
}

/** Shizuku's state and the button for the next step (nothing once it's ready). */
@Composable
fun ShizukuStep(state: ShizukuSupport.State) {
    val uri = LocalUriHandler.current
    if (state == ShizukuSupport.State.READY) return
    Text(
        when (state) {
            ShizukuSupport.State.NOT_INSTALLED -> "Shizuku isn't installed. Get it from its website or Google Play, start it, then come back."
            ShizukuSupport.State.NOT_RUNNING ->
                "Shizuku is installed but not running. Start it with wireless debugging (Android 11+) or adb - " +
                    "it has to be started again after each reboot."
            else -> "Shizuku is running. Allow SegAP to use it."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    when (state) {
        ShizukuSupport.State.NOT_INSTALLED -> Button(onClick = { uri.openUri(ShizukuSupport.DOWNLOAD_URL) }) { Text("Get Shizuku") }
        ShizukuSupport.State.NOT_RUNNING -> Button(onClick = { HotspotController.openShizuku() }) { Text("Open Shizuku") }
        else -> Button(onClick = { HotspotController.requestShizukuPermission() }) { Text("Allow SegAP in Shizuku") }
    }
}

/** Hotspot tab: which engine to use. Applies at once (it's not part of the hotspot settings). */
@Composable
fun EngineSection() {
    val mode by HotspotController.engineMode.collectAsStateWithLifecycle()
    val backend by HotspotController.backend.collectAsStateWithLifecycle()
    val shizuku by HotspotController.shizukuState.collectAsStateWithLifecycle()
    val root by HotspotController.rootAvailable.collectAsStateWithLifecycle()
    val owner by HotspotController.owner.collectAsStateWithLifecycle()
    val busy by HotspotController.busy.collectAsStateWithLifecycle()
    val locked = HotspotController.engineLocked(owner, shizuku, busy)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("Engine")
        Text(
            when (backend) {
                BackendKind.ROOT -> "In use: Root - SegAP's own access point, with every feature."
                BackendKind.SHIZUKU -> "In use: Shizuku - the phone's own hotspot, one network. Root-only settings are greyed out."
                null -> "In use: none - see the Status tab."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (locked) Text(
            "Stop the hotspot to change the engine.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        ) else if (owner != null) Text(
            "SegAP can't reach Shizuku, which runs the hotspot. Changing the engine forgets that hotspot - " +
                "turn it off in Quick Settings if it's still on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        EngineRow("Automatic", "Root when available, Shizuku otherwise.", mode == EngineMode.AUTO, !locked) {
            HotspotController.setEngineMode(EngineMode.AUTO)
        }
        EngineRow(
            "Root only",
            if (root == false) "Root isn't available right now." else "Never fall back to Shizuku.",
            mode == EngineMode.ROOT, !locked,
        ) { HotspotController.setEngineMode(EngineMode.ROOT) }
        EngineRow(
            "Shizuku only",
            "The phone's own hotspot, even when root is available. ${shizuku.message}",
            mode == EngineMode.SHIZUKU, !locked,
        ) { HotspotController.setEngineMode(EngineMode.SHIZUKU) }
        if (mode != EngineMode.ROOT && backend != BackendKind.ROOT) ShizukuStep(shizuku)
    }
}

@Composable
private fun EngineRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, onClick = onSelect).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
