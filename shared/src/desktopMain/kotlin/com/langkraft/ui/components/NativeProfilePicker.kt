package com.langkraft.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.langkraft.domain.model.*

@Composable
actual fun NativeProfilePicker(profiles: List<LocalProfile>, selected: ProfileId?, enabled: Boolean, onSelect: (ProfileId) -> Unit, modifier: Modifier) =
    ComposeProfilePicker(profiles, selected, enabled, onSelect, modifier)
