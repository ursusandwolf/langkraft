package com.langkraft.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class IngestRequest(val url: String)

@Serializable
data class ApiError(val message: String)
