package com.langkraft.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun NativeTextInput(value: String, onValueChange: (String) -> Unit, enabled: Boolean, modifier: Modifier, label: String, isUrl: Boolean = false, isPassword: Boolean = false)
