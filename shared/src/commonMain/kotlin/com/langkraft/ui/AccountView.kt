package com.langkraft.ui

import androidx.compose.runtime.*
import androidx.compose.material.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.langkraft.data.sync.UserSession
import com.langkraft.data.sync.ISyncManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AccountView(session: UserSession, sync: ISyncManager) {
    val token by session.token.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun submit(register: Boolean) { scope.launch {
        busy = true; error = null
        try { session.signIn(email.trim(), password, if (register) name else null); password = ""; sync.sync(force=true) }
        catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { error = failure.message }
        finally { busy = false }
    } }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Account & sync", style=MaterialTheme.typography.h5)
        if (token == null) {
            OutlinedTextField(email, { email=it }, label={Text("Email")})
            OutlinedTextField(password, { password=it }, label={Text("Password")}, visualTransformation=PasswordVisualTransformation())
            OutlinedTextField(name, { name=it }, label={Text("Display name (registration)")})
            Button(onClick={submit(false)}, enabled=!busy && email.isNotBlank() && password.isNotBlank()) { Text("Sign in") }
            Button(onClick={submit(true)}, enabled=!busy && email.isNotBlank() && password.isNotBlank() && name.isNotBlank()) { Text("Create account") }
        } else {
            Text("Signed in")
            Button(onClick={scope.launch { sync.sync(force=true) }}) { Text("Sync now") }
            Button(onClick={scope.launch { session.signOut() }}) { Text("Sign out") }
        }
        if (busy) CircularProgressIndicator()
        error?.let { Text(it, color=MaterialTheme.colors.error) }
        val syncError by sync.syncError.collectAsState()
        syncError?.let { Text(it, color=MaterialTheme.colors.error) }
    }
}
