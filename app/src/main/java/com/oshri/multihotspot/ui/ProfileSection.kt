package com.oshri.multihotspot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oshri.multihotspot.HotspotController
import com.oshri.multihotspot.model.HotspotConfig
import com.oshri.multihotspot.model.HotspotProfile
import kotlinx.coroutines.launch

/** Name dialog request: a new profile, or renaming [profile]. */
private data class NameEdit(val profile: HotspotProfile?)

/**
 * Hotspot tab, top: the saved hotspot profiles (Wi-Fi name, channel, radio settings); tapping one makes it the one
 * in use, at once. [dirty]: the tab has unsaved edits (switching would lose them).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileSection(config: HotspotConfig, dirty: Boolean, running: Boolean, busy: Boolean) {
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var nameEdit by remember { mutableStateOf<NameEdit?>(null) }
    var deleting by remember { mutableStateOf<HotspotProfile?>(null) }
    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }
    val active = config.profiles.firstOrNull { it.id == config.activeProfile }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Profile")
            Box(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Profile actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("New profile from these settings") },
                        leadingIcon = { Icon(Icons.Default.Add, null) },
                        onClick = { menu = false; if (dirty) say("Save or discard your changes first") else nameEdit = NameEdit(null) },
                    )
                    if (active != null) DropdownMenuItem(
                        text = { Text("Rename \"${active.name}\"") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menu = false; nameEdit = NameEdit(active) },
                    )
                    config.profiles.filter { it.id != config.activeProfile }.forEach { p ->
                        DropdownMenuItem(
                            text = { Text("Delete \"${p.name}\"") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menu = false; deleting = p },
                        )
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            config.profiles.forEach { p ->
                FilterChip(
                    selected = p.id == config.activeProfile,
                    enabled = !busy,
                    onClick = {
                        when {
                            p.id == config.activeProfile -> Unit
                            dirty -> say("Save or discard your changes first")
                            else -> {
                                val err = HotspotController.selectProfile(p.id)
                                say(err ?: if (running) "Switched to ${p.name} - the hotspot restarts with it" else "Switched to ${p.name}")
                            }
                        }
                    },
                    label = { Text(p.name) },
                )
            }
        }
        Text(
            "A profile holds the hotspot settings below: Wi-Fi name, channel, max devices and radio settings. The networks " +
                "and their passwords are shared by all profiles." + if (running) " Switching profile restarts the hotspot." else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    nameEdit?.let { edit ->
        var name by remember(edit) { mutableStateOf(edit.profile?.name ?: "Profile ${config.profiles.size + 1}") }
        var error by remember(edit) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { nameEdit = null },
            title = { Text(if (edit.profile == null) "New profile" else "Rename profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (edit.profile == null) Text(
                        "Starts as a copy of the saved settings of \"${active?.name ?: ""}\" and becomes the profile in use. " +
                            "Then change its settings below and Save.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = name, onValueChange = { name = it; error = null }, label = { Text("Name") }, singleLine = true,
                        isError = error != null, supportingText = { error?.let { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    error = if (edit.profile == null) HotspotController.addProfile(name) else HotspotController.renameProfile(edit.profile.id, name)
                    if (error == null) nameEdit = null
                }) { Text(if (edit.profile == null) "Create" else "Rename") }
            },
            dismissButton = { TextButton(onClick = { nameEdit = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${p.name}\"?") },
            text = { Text("Its settings (Wi-Fi name \"${p.ssid}\", channel, radio) are removed. The networks aren't affected.") },
            confirmButton = {
                TextButton(onClick = {
                    HotspotController.deleteProfile(p.id)?.let { say(it) }
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
