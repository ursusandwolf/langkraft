package com.langkraft.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.langkraft.data.profile.ProfileController
import com.langkraft.ui.components.NativeTextInput
import com.langkraft.ui.components.NativeProfilePicker

@Composable
fun ProfilesPanel(controller: ProfileController, enabled: Boolean = true) {
    val state by controller.state.collectAsState()
    var name by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Library: ${state.current?.name ?: "Opening…"}", style = MaterialTheme.typography.subtitle1)
        NativeProfilePicker(state.profiles, state.current?.id, enabled && !state.busy,
            { id -> if (id != state.current?.id) controller.select(id) }, Modifier.fillMaxWidth().height(44.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NativeTextInput(name, { name = it }, enabled && !state.busy, Modifier.weight(1f).height(56.dp), "New library name")
            Button(onClick = { controller.create(name); name = "" }, enabled = enabled && !state.busy && name.isNotBlank()) { Text("Create library") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colors.error) }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
