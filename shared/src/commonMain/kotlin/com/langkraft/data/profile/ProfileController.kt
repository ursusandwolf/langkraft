package com.langkraft.data.profile

import com.langkraft.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

interface LocalProfileStore {
    suspend fun list(): List<LocalProfile>
    suspend fun selected(): ProfileId
    suspend fun create(name: String): LocalProfile
    suspend fun select(id: ProfileId)
}

data class ProfilesState(
    val profiles: List<LocalProfile> = emptyList(),
    val current: LocalProfile? = null,
    val busy: Boolean = false,
    val error: String? = null
)

/** Owned by the application host, so switching does not cancel its own coroutine. */
class ProfileController(
    private val store: LocalProfileStore,
    private val scope: CoroutineScope,
    private val open: suspend (LocalProfile) -> Unit
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ProfilesState())
    val state = mutableState.asStateFlow()

    fun start() = perform {
        val profiles = store.list()
        mutableState.value = mutableState.value.copy(profiles = profiles)
        val selected = store.selected()
        openProfile(profiles.single { it.id == selected })
    }

    fun select(id: ProfileId) = perform { openProfile(store.list().single { it.id == id }) }

    fun create(name: String) = perform { openProfile(store.create(name.trim())) }

    private suspend fun openProfile(profile: LocalProfile) {
        mutableState.value = mutableState.value.copy(profiles = store.list())
        open(profile)
        store.select(profile.id)
        mutableState.value = mutableState.value.copy(current = profile)
    }

    private fun perform(action: suspend () -> Unit) = scope.launch {
        if (!mutex.tryLock()) return@launch
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutableState.value = mutableState.value.copy(current = null, error = failure.message ?: "Cannot open local profile") }
        finally { mutableState.value = mutableState.value.copy(busy = false); mutex.unlock() }
    }
}
