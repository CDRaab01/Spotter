package com.spotter.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
        /** Default workout cadence (train every N days) when the user hasn't set one. */
        const val DEFAULT_CADENCE_DAYS = 2

        /** Hour of day (local) the workout-morning nudge fires. */
        const val NUDGE_HOUR = 8

        /** Default quiet-hours window (nudge is suppressed if its fire time falls inside). */
        const val DEFAULT_QUIET_START_HOUR = 21
        const val DEFAULT_QUIET_END_HOUR = 7

        private val DARK_MODE = stringPreferencesKey("pref_dark_mode")
        private val WEIGHT_UNIT = stringPreferencesKey("pref_weight_unit")
        private val DISTANCE_UNIT = stringPreferencesKey("pref_distance_unit")
        private val WORKOUT_CADENCE_DAYS = intPreferencesKey("pref_workout_cadence_days")
        private val ONBOARDING_DONE = booleanPreferencesKey("pref_onboarding_done")
        private val PROFILE_EXPERIENCE = stringPreferencesKey("pref_experience")
        private val PROFILE_GOAL = stringPreferencesKey("pref_goal")
        private val PROFILE_EQUIPMENT = stringPreferencesKey("pref_equipment")
        private val PROFILE_AGE_GROUP = stringPreferencesKey("pref_age_group")
        private val PROFILE_LIMITATIONS = stringPreferencesKey("pref_limitations")
        private val SERVER_URL = stringPreferencesKey("pref_server_url")
        private val ACTIVE_CARDIO_PROGRAM = stringPreferencesKey("pref_active_cardio_program_id")
        private val WORKOUT_NUDGE_ENABLED = booleanPreferencesKey("pref_workout_nudge_enabled")
        private val QUIET_START_HOUR = intPreferencesKey("pref_quiet_start_hour")
        private val QUIET_END_HOUR = intPreferencesKey("pref_quiet_end_hour")
    }

    /**
     * Opt-in (default OFF): fire a local morning notification on days the active program schedules a
     * workout. See [com.spotter.util.nudge.WorkoutNudgeWorker]. Client-side only; never nags on rest
     * days or when a session is already logged/underway today.
     */
    val workoutNudgeEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[WORKOUT_NUDGE_ENABLED] ?: false
    }

    /** Quiet-hours window (local hours); the nudge is suppressed when its fire time falls inside. */
    val quietStartHour: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[QUIET_START_HOUR] ?: DEFAULT_QUIET_START_HOUR).coerceIn(0, 23)
    }

    val quietEndHour: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[QUIET_END_HOUR] ?: DEFAULT_QUIET_END_HOUR).coerceIn(0, 23)
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

    /** Days between workouts (every N days), used to project upcoming workout dates. */
    val workoutCadenceDays: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[WORKOUT_CADENCE_DAYS] ?: DEFAULT_CADENCE_DAYS).coerceIn(1, 14)
    }

    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ONBOARDING_DONE] ?: false
    }

    /**
     * Id of the cardio program the user has added to their schedule (e.g. "c25k"), or null when
     * none is active. Cardio program *definitions* are static client-side, so — unlike strength
     * [com.spotter.data.local.entity.WorkoutProgramEntity] — there is no server "is_active" flag;
     * this client-side preference is the source of truth for "do upcoming cardio runs show up?".
     */
    val activeCardioProgramId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[ACTIVE_CARDIO_PROGRAM]?.takeIf { it.isNotBlank() }
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

    suspend fun setWorkoutCadenceDays(value: Int) {
        context.dataStore.edit { it[WORKOUT_CADENCE_DAYS] = value.coerceIn(1, 14) }
    }

    suspend fun setServerUrl(value: String) {
        context.dataStore.edit { it[SERVER_URL] = value }
    }

    suspend fun setWorkoutNudgeEnabled(value: Boolean) {
        context.dataStore.edit { it[WORKOUT_NUDGE_ENABLED] = value }
    }

    suspend fun setQuietHours(startHour: Int, endHour: Int) {
        context.dataStore.edit { prefs ->
            prefs[QUIET_START_HOUR] = startHour.coerceIn(0, 23)
            prefs[QUIET_END_HOUR] = endHour.coerceIn(0, 23)
        }
    }

    /** Adds (non-null id) or removes (null) the active cardio program from the schedule. */
    suspend fun setActiveCardioProgram(id: String?) {
        context.dataStore.edit { prefs ->
            if (id.isNullOrBlank()) prefs.remove(ACTIVE_CARDIO_PROGRAM) else prefs[ACTIVE_CARDIO_PROGRAM] = id
        }
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

    /**
     * Marks onboarding complete without collecting a questionnaire profile. Used after a normal
     * login or "Sign in with Dragonfly": the account already exists, so the new-user intro must
     * be skipped (the flag is device-local, so a returning user on a fresh install would otherwise
     * be re-onboarded every launch). The AI intake protocol fills any missing profile gaps later.
     */
    suspend fun setOnboardingDone() {
        context.dataStore.edit { it[ONBOARDING_DONE] = true }
    }

    /**
     * Clears the saved questionnaire profile and onboarding flag so the user is sent back
     * through the onboarding questionnaire. Used by account reset. Leaves app preferences
     * (theme, units, server URL) intact.
     */
    suspend fun clearOnboarding() {
        context.dataStore.edit { prefs ->
            prefs.remove(ONBOARDING_DONE)
            prefs.remove(PROFILE_EXPERIENCE)
            prefs.remove(PROFILE_GOAL)
            prefs.remove(PROFILE_EQUIPMENT)
            prefs.remove(PROFILE_AGE_GROUP)
            prefs.remove(PROFILE_LIMITATIONS)
        }
    }
}
