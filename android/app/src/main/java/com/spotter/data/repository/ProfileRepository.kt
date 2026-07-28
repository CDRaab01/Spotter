package com.spotter.data.repository

import com.spotter.data.model.ProfileOut
import com.spotter.data.model.ProfileUpdate
import com.spotter.data.remote.ApiService
import com.spotter.util.AppPreferences
import com.spotter.util.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.inject.Inject

/**
 * The persistent training profile (equipment, experience, goal, age group, limitations) — the
 * context the AI coach is given so it stops re-asking what equipment the user has.
 *
 * **The server is the source of truth**; [AppPreferences.userProfile] stays as the offline mirror,
 * so existing readers (`AiChatViewModel`, `HomeViewModel`) keep working unchanged and a fresh
 * install repopulates from the server instead of starting blank.
 *
 * Writes follow the app-wide write-through + drain-queue pattern (see [MetricRepository]): the
 * mirror is written first so an edit is never lost, then pushed. The degrade rule is the app's
 * usual one — **`IOException` queues locally; `retrofit2.HttpException` surfaces**, because the
 * server answered and swallowing its error would tell the user a lie.
 */
class ProfileRepository @Inject constructor(
    private val api: ApiService,
    private val appPreferences: AppPreferences,
) {
    /** The mirror, as observed by the rest of the app. */
    val profile: Flow<UserProfile> = appPreferences.userProfile

    /** One-shot read of the mirror. */
    suspend fun current(): UserProfile = appPreferences.userProfile.first()

    /**
     * Pulls the server profile into the mirror. Drains a queued offline edit first — that edit is
     * newer than anything the server can return, so pulling over it would silently undo what the
     * user typed. Offline (or with an undeliverable queued edit) this is a no-op that leaves the
     * mirror alone; it never throws, so callers can fire it as part of a sync round.
     *
     * @return true when the server was reached and the mirror now matches it.
     */
    suspend fun refresh(): Boolean {
        if (!drainPending()) return false
        return try {
            appPreferences.setProfile(api.getProfile().toUserProfile())
            true
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Saves an edited profile: the mirror immediately, then the server.
     *
     * @return true when the server acknowledged it, false when it was queued for the next drain.
     * @throws retrofit2.HttpException (and anything else non-connectivity) so the caller can show
     *   the failure — the mirror still holds the edit, but the user must be told it didn't land.
     */
    suspend fun save(profile: UserProfile): Boolean {
        appPreferences.setProfile(profile)
        return try {
            appPreferences.setProfile(api.updateProfile(profile.toUpdate()).toUserProfile())
            appPreferences.setProfileSyncPending(false)
            true
        } catch (_: IOException) {
            appPreferences.setProfileSyncPending(true)
            false
        }
    }

    /**
     * Pending-drain entry point for [com.spotter.util.NetworkSyncObserver] / the Home sync round.
     * Pushes a profile edit made offline; never throws.
     *
     * @return true when nothing is queued any more.
     */
    suspend fun syncPending(): Boolean = drainPending()

    private suspend fun drainPending(): Boolean {
        if (!appPreferences.profileSyncPending.first()) return true
        return try {
            appPreferences.setProfile(api.updateProfile(current().toUpdate()).toUserProfile())
            appPreferences.setProfileSyncPending(false)
            true
        } catch (_: IOException) {
            false // still offline — keep it queued
        } catch (_: Exception) {
            // The server answered with an error, so this edit is undeliverable as-is. Drop it from
            // the queue rather than retrying it on every sync round forever; the mirror keeps the
            // user's values until a later pull replaces them.
            appPreferences.setProfileSyncPending(false)
            false
        }
    }
}

private fun ProfileOut.toUserProfile() = UserProfile(
    experience = experience.orEmpty(),
    goal = goal.orEmpty(),
    equipment = equipment.orEmpty(),
    ageGroup = ageGroup.orEmpty(),
    limitations = limitations.orEmpty(),
)

/**
 * Every field is sent explicitly: the editing surfaces (Settings, onboarding) own the whole
 * profile, so a blank field means "cleared" — which the contract expresses as an empty string,
 * not an omitted key.
 */
private fun UserProfile.toUpdate() = ProfileUpdate(
    equipment = equipment,
    experience = experience,
    goal = goal,
    ageGroup = ageGroup,
    limitations = limitations,
)
