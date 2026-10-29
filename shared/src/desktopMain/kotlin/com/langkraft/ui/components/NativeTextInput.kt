package com.langkraft.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.ui.Modifier

@Composable
actual fun NativeTextInput(value: String, onValueChange: (String) -> Unit, enabled: Boolean, modifier: Modifier, label: String, isUrl: Boolean, isPassword: Boolean) {
    OutlinedTextField(value=value, onValueChange=onValueChange, enabled=enabled, modifier=modifier, label={Text(label)},
        visualTransformation = if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
}
