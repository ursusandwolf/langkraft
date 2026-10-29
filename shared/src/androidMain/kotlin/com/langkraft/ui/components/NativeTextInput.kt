package com.langkraft.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.ui.Modifier

@Composable
actual fun NativeTextInput(value: String, onValueChange: (String) -> Unit, enabled: Boolean, modifier: Modifier, label: String, isUrl: Boolean) {
    OutlinedTextField(value=value, onValueChange=onValueChange, enabled=enabled, modifier=modifier, label={Text(label)})
}
