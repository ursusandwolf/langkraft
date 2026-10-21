package com.langkraft.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.ui.Modifier

@Composable
actual fun NativeUrlInput(value: String, onValueChange: (String) -> Unit, enabled: Boolean, modifier: Modifier) {
    OutlinedTextField(value=value, onValueChange=onValueChange, enabled=enabled, modifier=modifier, label={Text("YouTube URL")})
}
