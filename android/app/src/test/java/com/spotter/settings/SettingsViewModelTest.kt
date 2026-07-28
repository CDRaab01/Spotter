package com.spotter.settings

import com.spotter.data.local.SpotterDatabase
import com.spotter.data.model.UserOut
import com.spotter.data.model.VersionOut
import com.spotter.data.remote.ApiService
import com.spotter.data.repository.ProgramRepository
import com.spotter.ui.settings.SettingsViewModel
import com.spotter.util.AppPreferences
import com.spotter.util.DarkModePreference
import com.spotter.util.DistanceUnit
import com.spotter.util.TokenStore
import com.spotter.util.UiState
import com.spotter.util.WeightUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var api: ApiService
    private lateinit var tokenStore: TokenStore
    private lateinit var appPreferences: AppPreferences
    private lateinit var database: SpotterDatabase
    private lateinit var programRepository: ProgramRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        api = mock()
        tokenStore = mock()
        appPreferences = mock()
        database = mock()
        programRepository = mock()
        whenever(appPreferences.darkMode).thenReturn(flowOf(DarkModePreference.SYSTEM))
        whenever(appPreferences.weightUnit).thenReturn(flowOf(WeightUnit.LBS))
        whenever(appPreferences.distanceUnit).thenReturn(flowOf(DistanceUnit.MI))
        whenever(appPreferences.workoutCadenceDays).thenReturn(flowOf(2))
        whenever(appPreferences.serverUrl).thenReturn(flowOf("http://10.0.2.2:8000/"))
        whenever(programRepository.programs).thenReturn(flowOf(emptyList()))
    }

    private val sampleVersion =
        VersionOut(name = "Spotter API", version = "0.1.0", commit = "a1b2c3d", builtAt = "2026-06-06T12:00:00Z")

    private fun createViewModel() =
        SettingsViewModel(api, tokenStore, appPreferences, database, programRepository, testDispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads user`() = runTest(testDispatcher) {
        val user = UserOut(id = "u-1", name = "Alice", email = "alice@example.com")
        whenever(api.getMe()).thenReturn(user)

        viewModel = createViewModel()
        advanceTimeBy(200)

        assertIs<UiState.Success<UserOut>>(viewModel.user.value)
        assertEquals(user, (viewModel.user.value as UiState.Success).data)
    }

    @Test
    fun `init loads server version`() = runTest(testDispatcher) {
        whenever(api.getMe()).thenReturn(UserOut(id = "u-1", name = "Alice", email = "a@example.com"))
        whenever(api.getServerVersion()).thenReturn(sampleVersion)

        viewModel = createViewModel()
        advanceTimeBy(200)

        assertIs<UiState.Success<VersionOut>>(viewModel.serverVersion.value)
        assertEquals(sampleVersion, (viewModel.serverVersion.value as UiState.Success).data)
    }

    @Test
    fun `server version reflects an unreachable server as error`() = runTest(testDispatcher) {
        whenever(api.getMe()).thenReturn(UserOut(id = "u-1", name = "Alice", email = "a@example.com"))
        whenever(api.getServerVersion()).thenThrow(RuntimeException("timeout"))

        viewModel = createViewModel()
        advanceTimeBy(200)

        assertIs<UiState.Error>(viewModel.serverVersion.value)
    }

    @Test
    fun `setWorkoutCadenceDays delegates to appPreferences`() = runTest(testDispatcher) {
        whenever(api.getMe()).thenReturn(UserOut(id = "u-1", name = "Alice", email = "a@example.com"))

        viewModel = createViewModel()
        advanceTimeBy(200)

        viewModel.setWorkoutCadenceDays(3)
        advanceTimeBy(200)

        verify(appPreferences).setWorkoutCadenceDays(3)
    }

    @Test
    fun `logout clears token and emits navigateToLogin`() = runTest(testDispatcher) {
        val user = UserOut(id = "u-1", name = "Alice", email = "alice@example.com")
        whenever(api.getMe()).thenReturn(user)

        viewModel = createViewModel()
        advanceTimeBy(200)

        val events = mutableListOf<Unit>()
        val job = launch { viewModel.navigateToLogin.collect { events.add(it) } }

        viewModel.logout()
        advanceTimeBy(200)

        verify(tokenStore).clear()
        assertEquals(1, events.size)
        job.cancel()
    }

    @Test
    fun `resetAccount wipes server and local state then navigates to onboarding`() = runTest(testDispatcher) {
        val user = UserOut(id = "u-1", name = "Alice", email = "alice@example.com")
        whenever(api.getMe()).thenReturn(user)

        viewModel = createViewModel()
        advanceTimeBy(200)

        val events = mutableListOf<Unit>()
        val job = launch { viewModel.navigateToOnboarding.collect { events.add(it) } }

        viewModel.resetAccount()
        advanceTimeBy(200)

        // Server wipe must happen while still authenticated, before local state is cleared.
        verify(api).resetAccount()
        verify(database).clearAllTables()
        verify(appPreferences).clearOnboarding()
        // Deliberately NOT a sign-out: bouncing through login re-set the onboarding flag
        // before the login screen read it, so the promised questionnaire never showed.
        verify(tokenStore, never()).clear()
        assertEquals(1, events.size)
        assertEquals(false, viewModel.resetting.value)
        job.cancel()
    }
}
