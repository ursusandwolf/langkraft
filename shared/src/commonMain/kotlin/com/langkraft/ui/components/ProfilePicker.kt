package com.langkraft.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.langkraft.domain.model.*

@Composable
expect fun NativeProfilePicker(profiles: List<LocalProfile>, selected: ProfileId?, enabled: Boolean, onSelect: (ProfileId) -> Unit, modifier: Modifier)

@Composable
fun ComposeProfilePicker(profiles: List<LocalProfile>, selected: ProfileId?, enabled: Boolean, onSelect: (ProfileId) -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(profiles.firstOrNull { it.id == selected }?.name ?: "Select library")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            profiles.forEach { profile -> DropdownMenuItem(onClick = { expanded = false; onSelect(profile.id) }) { Text(profile.name) } }
        }
    }
}
