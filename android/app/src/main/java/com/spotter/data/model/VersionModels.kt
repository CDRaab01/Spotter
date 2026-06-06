package com.spotter.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Server build info from GET /version — used to confirm a redeploy landed. */
@Serializable
data class VersionOut(
    val name: String,
    val version: String,
    val commit: String,
    @SerialName("built_at") val builtAt: String,
)
