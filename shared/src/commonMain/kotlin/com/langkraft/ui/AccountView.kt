package com.langkraft.ui

import androidx.compose.runtime.*
import androidx.compose.material.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.langkraft.data.sync.UserSession
import com.langkraft.data.sync.ISyncManager
import com.langkraft.data.sync.GuestBindingPreview
import com.langkraft.data.profile.ProfileController
import com.langkraft.di.ClientLifetime
import com.langkraft.ui.components.NativeTextInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AccountView(session: UserSession, sync: ISyncManager, lifetime: ClientLifetime, profiles: ProfileController? = null) {
    val token by session.token.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var binding by remember { mutableStateOf<Pair<Boolean, GuestBindingPreview>?>(null) }
    val scope = lifetime.scope
    val syncing by sync.isSyncing.collectAsState()
    fun submit(register: Boolean, confirmed: Boolean = false) { scope.launch {
        busy = true; error = null
        try {
            val preview = if (confirmed) null else session.guestBindingPreview()
            if (preview != null) { binding = register to preview; return@launch }
            session.signIn(email.trim(), password, if (register) name else null, bindGuestWords = confirmed)
            password = ""; sync.sync(force=true)
        }
        catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { error = failure.message }
        finally { busy = false }
    } }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Account & sync", style=MaterialTheme.typography.h5)
        profiles?.let { ProfilesPanel(it, enabled = binding == null && !busy); Divider() }
        if (token == null) {
            if (binding == null) {
                NativeTextInput(email, { email=it }, !busy, Modifier.fillMaxWidth().height(56.dp), "Email")
                NativeTextInput(password, { password=it }, !busy, Modifier.fillMaxWidth().height(56.dp), "Password", isPassword = true)
                NativeTextInput(name, { name=it }, !busy, Modifier.fillMaxWidth().height(56.dp), "Display name (registration)")
                Button(onClick={submit(false)}, enabled=!busy && email.isNotBlank() && password.isNotBlank()) { Text("Sign in") }
                Button(onClick={submit(true)}, enabled=!busy && email.isNotBlank() && password.isNotBlank() && name.isNotBlank()) { Text("Create account") }
            }
        } else {
            Text("Signed in")
            Button(onClick={scope.launch { sync.sync(force=true) }}, enabled = !syncing) { Text("Sync now") }
            Button(onClick={scope.launch {
                busy = true; error = null
                try { session.signOut() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message }
                finally { busy = false }
            }}, enabled = !busy && !syncing) { Text("Sign out") }
        }
        if (busy) CircularProgressIndicator()
        error?.let { Text(it, color=MaterialTheme.colors.error) }
        val syncError by sync.syncError.collectAsState()
        syncError?.let { Text(it, color=MaterialTheme.colors.error) }
    }
    binding?.let { (register, preview) ->
        AlertDialog(
            onDismissRequest = { binding = null },
            title = { Text("Link this guest library?") },
            text = { Text("Link all ${preview.wordCount} saved words and ${preview.pendingChangeCount} pending changes to ${email.trim()}? The words stay in this library and will synchronize with that account. Use a separate library to keep them apart.") },
            confirmButton = { TextButton(onClick = { binding = null; submit(register, confirmed = true) }) { Text("Link and sign in") } },
            dismissButton = { TextButton(onClick = { binding = null }) { Text("Cancel") } }
        )
    }
}
