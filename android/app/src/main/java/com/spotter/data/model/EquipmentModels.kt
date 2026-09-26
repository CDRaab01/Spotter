package com.spotter.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One plate size and how many matching **pairs** of it the user owns (a barbell takes one per side). */
@Serializable
data class PlatePair(
    val weight: Double,
    val pairs: Int,
)

/**
 * What the user can actually load (`GET`/`PUT /users/me/equipment`) — bars, plate pairs, per-hand
 * dumbbells and the machine/cable stack step, all in [unit] ("lb" | "kg"). Plates are physical
 * objects stamped in one unit, so the inventory keeps its own unit instead of converting to the
 * app's canonical pounds; [com.spotter.util.Loading] converts at the edge.
 */
@Serializable
data class EquipmentInventory(
    val unit: String = "lb",
    val bars: List<Double> = emptyList(),
    val plates: List<PlatePair> = emptyList(),
    val dumbbells: List<Double> = emptyList(),
    @SerialName("stack_step") val stackStep: Double? = null,
)

/**
 * [configured] is false until the user saves an inventory; [inventory] is then the standard-gym
 * default the server is snapping suggestions to, so the editor starts from exactly that.
 */
@Serializable
data class EquipmentOut(
    val configured: Boolean = false,
    val inventory: EquipmentInventory = EquipmentInventory(),
)
