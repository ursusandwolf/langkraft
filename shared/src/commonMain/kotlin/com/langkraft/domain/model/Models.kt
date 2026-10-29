package com.langkraft.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ReviewQuality(val sm2Value: Int) {
    AGAIN(0),
    HARD(3),
    GOOD(4),
    EASY(5)
}
