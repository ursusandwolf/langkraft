package com.langkraft.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ProfileId(val value: String) {
    init {
        require(value.isEmpty() || Regex("(?:local-|recovered-(?:v2-)?)[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}").matches(value)) {
            "Invalid local profile identifier"
        }
    }
}

@Serializable
data class AccountId(val value: String) {
    init { require(value.isNotBlank()) { "Account ID must not be blank" } }
}

@Serializable
data class LocalProfile(val id: ProfileId, val name: String) {
    init { require(name.isNotBlank() && name.length <= 60 && name.none { it < ' ' }) { "Profile name must contain 1 to 60 printable characters" } }
}

val defaultLocalProfile = LocalProfile(ProfileId(""), "Default library")
