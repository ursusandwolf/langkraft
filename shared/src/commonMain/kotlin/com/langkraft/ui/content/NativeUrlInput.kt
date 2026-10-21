package com.langkraft.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun NativeUrlInput(value: String, onValueChange: (String) -> Unit, enabled: Boolean, modifier: Modifier)
