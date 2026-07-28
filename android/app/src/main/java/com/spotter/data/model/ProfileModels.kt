package com.spotter.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The persistent training profile (`GET`/`PATCH /users/me/profile`) — the coach's memory of what
 * equipment the user actually has, their experience, goal, age group and limitations.
 *
 * Before this existed the profile lived only in the device's DataStore and could only be written
 * once, by the onboarding questionnaire — so buying a barbell, changing gyms or reinstalling all
 * silently reverted the coach to "I don't know what you own".
 *
 * Every field is nullable with a null default so a server that doesn't know a key (or hasn't been
 * asked for one) still parses.
 */
@Serializable
data class ProfileOut(
    val equipment: String? = null,
    val experience: String? = null,
    val goal: String? = null,
    @SerialName("age_group") val ageGroup: String? = null,
    val limitations: String? = null,
    @SerialName("profile_updated_at") val profileUpdatedAt: String? = null,
)

/**
 * A partial profile write. **Partial semantics:** an omitted key is left unchanged server-side, an
 * explicit empty string clears the field. Nulls are dropped on the wire (the shared `Json` leaves
 * `encodeDefaults` off), so "don't touch this" is expressed by leaving the property null.
 */
@Serializable
data class ProfileUpdate(
    val equipment: String? = null,
    val experience: String? = null,
    val goal: String? = null,
    @SerialName("age_group") val ageGroup: String? = null,
    val limitations: String? = null,
)
