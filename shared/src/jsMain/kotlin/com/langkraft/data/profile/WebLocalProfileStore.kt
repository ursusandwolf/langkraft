package com.langkraft.data.profile

import com.langkraft.data.storage.*
import com.langkraft.domain.model.*
import kotlinx.coroutines.await

class WebLocalProfileStore : LocalProfileStore {
    override suspend fun list(): List<LocalProfile> {
        val profiles = listLocalProfiles().await().map { LocalProfile(ProfileId(it.id), it.name) }
        return if (selectedProfile.startsWith("recovered-") && !selectedProfile.startsWith("recovered-v2-") && profiles.none { it.id.value == selectedProfile })
            profiles + LocalProfile(ProfileId(selectedProfile), selectedProfile)
        else profiles
    }
    override suspend fun selected() = ProfileId(selectedProfile)
    override suspend fun create(name: String): LocalProfile = createLocalProfile(name).await().let { LocalProfile(ProfileId(it.id), it.name) }
    // The URL is the per-tab selection; changing one tab never changes another tab's library.
    override suspend fun select(id: ProfileId) { localProfileUrl(id.value).await() }
}
