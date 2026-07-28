package com.spotter.data.repository

import com.spotter.data.model.ProfileOut
import com.spotter.data.model.ProfileUpdate
import com.spotter.data.remote.ApiService
import com.spotter.util.AppPreferences
import com.spotter.util.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The training profile is the coach's memory of the user's equipment. It must survive a bad
 * connection (queued, never dropped), must not lie about an HTTP failure, and must repopulate from
 * the server on a fresh install — the whole point of moving it off device-only storage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileRepositoryTest {

    private lateinit var api: ApiService
    private lateinit var prefs: FakeProfilePreferences
    private lateinit var repo: ProfileRepository

    private val edited = UserProfile(
        experience = "INTERMEDIATE",
        goal = "STRENGTH",
        equipment = "dumbbells up to 50lb, pull-up bar",
        ageGroup = "35_44",
        limitations = "left shoulder",
    )

    @Before
    fun setup() {
        api = mock()
        prefs = FakeProfilePreferences()
        repo = ProfileRepository(api, prefs.instance)
    }

    @Test
    fun `save writes the mirror and pushes the whole profile`() = runTest {
        whenever(api.updateProfile(any())).thenReturn(edited.toOut())

        assertTrue(repo.save(edited), "an acknowledged save reports as pushed")

        assertEquals(edited, prefs.profile.value)
        assertFalse(prefs.pending.value)
        // Every field goes out explicitly — a blank one means "cleared", which the contract
        // expresses as an empty string, not an omitted key.
        verify(api).updateProfile(
            ProfileUpdate(
                equipment = "dumbbells up to 50lb, pull-up bar",
                experience = "INTERMEDIATE",
                goal = "STRENGTH",
                ageGroup = "35_44",
                limitations = "left shoulder",
            ),
        )
    }

    @Test
    fun `save offline keeps the edit in the mirror and queues it`() = runTest {
        whenever(api.updateProfile(any())).thenAnswer { throw IOException("offline") }

        assertFalse(repo.save(edited), "an offline save must not claim the server has it")

        assertEquals(edited, prefs.profile.value, "the edit is never lost")
        assertTrue(prefs.pending.value)
    }

    @Test
    fun `a queued edit is pushed by the next drain`() = runTest {
        whenever(api.updateProfile(any()))
            .thenAnswer { throw IOException("offline") }
            .thenReturn(edited.toOut())

        repo.save(edited)
        assertTrue(prefs.pending.value)

        assertTrue(repo.syncPending())

        assertFalse(prefs.pending.value)
        assertEquals(edited, prefs.profile.value)
    }

    @Test
    fun `save surfaces an HTTP failure instead of silently queueing it`() = runTest {
        whenever(api.updateProfile(any())).thenThrow(httpException(422))

        assertFailsWith<HttpException> { repo.save(edited) }

        // The mirror still holds what the user typed, but nothing is queued: the server answered,
        // so this is a real error for the UI to report — not a connectivity retry.
        assertEquals(edited, prefs.profile.value)
        assertFalse(prefs.pending.value)
    }

    @Test
    fun `refresh populates the mirror from the server`() = runTest {
        whenever(api.getProfile()).thenReturn(edited.toOut())

        assertTrue(repo.refresh())

        assertEquals(edited, prefs.profile.value)
    }

    @Test
    fun `refresh maps missing server fields to blanks`() = runTest {
        whenever(api.getProfile()).thenReturn(ProfileOut(equipment = "full gym"))

        assertTrue(repo.refresh())

        assertEquals(UserProfile(equipment = "full gym"), prefs.profile.value)
    }

    @Test
    fun `refresh offline leaves the mirror alone`() = runTest {
        prefs.profile.value = edited
        whenever(api.getProfile()).thenAnswer { throw IOException("offline") }

        assertFalse(repo.refresh())

        assertEquals(edited, prefs.profile.value)
    }

    @Test
    fun `refresh never pulls over a queued offline edit`() = runTest {
        whenever(api.updateProfile(any())).thenAnswer { throw IOException("offline") }
        repo.save(edited)

        assertFalse(repo.refresh(), "still offline")

        // The stale server copy must not resurrect over what the user just typed.
        verify(api, never()).getProfile()
        assertEquals(edited, prefs.profile.value)
        assertTrue(prefs.pending.value)
    }

    @Test
    fun `an undeliverable queued edit is dropped from the queue rather than retried forever`() =
        runTest {
            whenever(api.updateProfile(any()))
                .thenAnswer { throw IOException("offline") }
                .thenThrow(httpException(422))

            repo.save(edited)
            assertTrue(prefs.pending.value)

            assertFalse(repo.syncPending())
            assertFalse(prefs.pending.value)
        }

    private fun httpException(code: Int) = HttpException(
        Response.error<Unit>(code, "".toResponseBody("application/json".toMediaType())),
    )

    private fun UserProfile.toOut() = ProfileOut(
        equipment = equipment, experience = experience, goal = goal,
        ageGroup = ageGroup, limitations = limitations,
        profileUpdatedAt = "2026-07-28T12:00:00Z",
    )
}

/**
 * [AppPreferences] is a final class over DataStore, so the profile slice is faked by stubbing the
 * four members [ProfileRepository] touches against in-memory state.
 */
private class FakeProfilePreferences {
    val profile = MutableStateFlow(UserProfile())
    val pending = MutableStateFlow(false)

    val instance: AppPreferences = mock<AppPreferences>().also { prefs ->
        whenever(prefs.userProfile).thenReturn(profile)
        whenever(prefs.profileSyncPending).thenReturn(pending)
        org.mockito.kotlin.wheneverBlocking { prefs.setProfile(any()) }.thenAnswer { invocation ->
            profile.value = invocation.getArgument(0)
            Unit
        }
        org.mockito.kotlin.wheneverBlocking { prefs.setProfileSyncPending(any()) }
            .thenAnswer { invocation ->
                pending.value = invocation.getArgument(0)
                Unit
            }
    }
}
