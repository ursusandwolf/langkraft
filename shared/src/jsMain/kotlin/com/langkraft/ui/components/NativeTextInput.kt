package com.langkraft.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event

/** Native input supplies paste, keyboard and browser accessibility on Canvas-based Compose. */
@Composable
actual fun NativeTextInput(value: String, onValueChange: (String) -> Unit, enabled: Boolean, modifier: Modifier, label: String, isUrl: Boolean) {
    val onChange by rememberUpdatedState(onValueChange)
    val input = remember(label, isUrl) {
        (document.createElement("input") as HTMLInputElement).apply {
            type = if (isUrl) "url" else "text"
            placeholder = label
            setAttribute("aria-label", label)
            setAttribute("autocomplete", if (isUrl) "url" else "off")
            style.cssText = "position:fixed;box-sizing:border-box;border:1px solid #718078;border-radius:4px;" +
                "padding:12px 16px;font:16px system-ui,sans-serif;color:#20372c;background:#f5f7f4;z-index:1;"
        }
    }
    DisposableEffect(input) {
        val listener: (Event) -> Unit = { onChange(input.value) }
        input.addEventListener("input", listener)
        document.body!!.appendChild(input)
        onDispose { input.removeEventListener("input", listener); input.remove() }
    }
    SideEffect {
        if (input.value != value) input.value = value
        input.disabled = !enabled
    }
    Box(modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        val scale = window.devicePixelRatio
        input.style.left = "${bounds.left / scale}px"
        input.style.top = "${bounds.top / scale}px"
        input.style.width = "${bounds.width / scale}px"
        input.style.height = "${bounds.height / scale}px"
    })
}
