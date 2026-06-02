package com.spotter.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.spotter.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class DarkModePreference { SYSTEM, LIGHT, DARK }
enum class WeightUnit { LBS, KG }
enum class DistanceUnit { MI, KM }

data class UserProfile(
    val experience: String = "",
    val goal: String = "",
    val equipment: String = "",
    val ageGroup: String = "",
    val limitations: String = "",
) {
    fun isEmpty() = experience.isBlank() && goal.isBlank()

    fun toContextString(): String {
        if (isEmpty()) return ""
        val parts = mutableListOf<String>()
        if (experience.isNotBlank()) parts.add("Experience: ${experience.lowercase()}")
        if (goal.isNotBlank()) parts.add("Goal: ${goal.replace('_', ' ').lowercase()}")
        if (equipment.isNotBlank()) parts.add("Equipment: ${equipment.replace('_', ' ').lowercase()}")
        if (ageGroup.isNotBlank()) parts.add("Age group: ${ageGroup.replace('_', '-').lowercase()}")
        if (limitations.isNotBlank()) parts.add("Limitations/injuries: ${limitations.lowercase()}")
        return "User profile — ${parts.joinToString(", ")}."
    }
}

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        private val DARK_MODE = stringPreferencesKey("pref_dark_mode")
        private val WEIGHT_UNIT = stringPreferencesKey("pref_weight_unit")
        private val DISTANCE_UNIT = stringPreferencesKey("pref_distance_unit")
        private val ONBOARDING_DONE = booleanPreferencesKey("pref_onboarding_done")
        private val PROFILE_EXPERIENCE = stringPreferencesKey("pref_experience")
        private val PROFILE_GOAL = stringPreferencesKey("pref_goal")
        private val PROFILE_EQUIPMENT = stringPreferencesKey("pref_equipment")
        private val PROFILE_AGE_GROUP = stringPreferencesKey("pref_age_group")
        private val PROFILE_LIMITATIONS = stringPreferencesKey("pref_limitations")
        private val SERVER_URL = stringPreferencesKey("pref_server_url")
    }

    /** Base URL of the Spotter server. Defaults to the build-time value when unset. */
    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SERVER_URL]?.takeIf { it.isNotBlank() } ?: BuildConfig.SERVER_URL
    }

    val darkMode: Flow<DarkModePreference> = context.dataStore.data.map { prefs ->
        prefs[DARK_MODE]?.let { runCatching { DarkModePreference.valueOf(it) }.getOrNull() }
            ?: DarkModePreference.SYSTEM
    }

    val weightUnit: Flow<WeightUnit> = context.dataStore.data.map { prefs ->
        prefs[WEIGHT_UNIT]?.let { runCatching { WeightUnit.valueOf(it) }.getOrNull() }
            ?: WeightUnit.LBS
    }

    val distanceUnit: Flow<DistanceUnit> = context.dataStore.data.map { prefs ->
        prefs[DISTANCE_UNIT]?.let { runCatching { DistanceUnit.valueOf(it) }.getOrNull() }
            ?: DistanceUnit.MI
    }

    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ONBOARDING_DONE] ?: false
    }

    val userProfile: Flow<UserProfile> = context.dataStore.data.map { prefs ->
        UserProfile(
            experience = prefs[PROFILE_EXPERIENCE] ?: "",
            goal = prefs[PROFILE_GOAL] ?: "",
            equipment = prefs[PROFILE_EQUIPMENT] ?: "",
            ageGroup = prefs[PROFILE_AGE_GROUP] ?: "",
            limitations = prefs[PROFILE_LIMITATIONS] ?: "",
        )
    }

    suspend fun setDarkMode(value: DarkModePreference) {
        context.dataStore.edit { it[DARK_MODE] = value.name }
    }

    suspend fun setWeightUnit(value: WeightUnit) {
        context.dataStore.edit { it[WEIGHT_UNIT] = value.name }
    }

    suspend fun setDistanceUnit(value: DistanceUnit) {
        context.dataStore.edit { it[DISTANCE_UNIT] = value.name }
    }

    suspend fun setServerUrl(value: String) {
        context.dataStore.edit { it[SERVER_URL] = value }
    }

    suspend fun saveProfile(profile: UserProfile) {
        context.dataStore.edit { prefs ->
            prefs[PROFILE_EXPERIENCE] = profile.experience
            prefs[PROFILE_GOAL] = profile.goal
            prefs[PROFILE_EQUIPMENT] = profile.equipment
            prefs[PROFILE_AGE_GROUP] = profile.ageGroup
            prefs[PROFILE_LIMITATIONS] = profile.limitations
            prefs[ONBOARDING_DONE] = true
        }
    }
}
