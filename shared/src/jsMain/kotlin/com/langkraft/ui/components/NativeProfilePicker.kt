package com.langkraft.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.langkraft.domain.model.*
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.events.Event

@Composable
actual fun NativeProfilePicker(profiles: List<LocalProfile>, selected: ProfileId?, enabled: Boolean, onSelect: (ProfileId) -> Unit, modifier: Modifier) {
    val onChange by rememberUpdatedState(onSelect)
    val select = remember(profiles) {
        (document.createElement("select") as HTMLSelectElement).apply {
            setAttribute("aria-label", "Libraries")
            style.cssText = "position:fixed;box-sizing:border-box;padding:8px;font:16px system-ui;z-index:1;"
            add((document.createElement("option") as HTMLOptionElement).apply {
                value = "__select__"; textContent = "Select library"; disabled = true
            })
            profiles.forEach { profile ->
                add((document.createElement("option") as HTMLOptionElement).apply { value = profile.id.value; textContent = profile.name })
            }
        }
    }
    DisposableEffect(select) {
        val listener: (Event) -> Unit = { onChange(ProfileId(select.value)) }
        select.addEventListener("change", listener)
        document.body!!.appendChild(select)
        onDispose { select.removeEventListener("change", listener); select.remove() }
    }
    SideEffect { select.disabled = !enabled; select.value = selected?.value ?: "__select__" }
    Box(modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        val scale = window.devicePixelRatio
        select.style.left = "${bounds.left / scale}px"; select.style.top = "${bounds.top / scale}px"
        select.style.width = "${bounds.width / scale}px"; select.style.height = "${bounds.height / scale}px"
    })
}
