package com.spotter.settings

import com.spotter.data.model.UserOut
import com.spotter.data.remote.ApiService
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
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        api = mock()
        tokenStore = mock()
        appPreferences = mock()
        whenever(appPreferences.darkMode).thenReturn(flowOf(DarkModePreference.SYSTEM))
        whenever(appPreferences.weightUnit).thenReturn(flowOf(WeightUnit.LBS))
        whenever(appPreferences.distanceUnit).thenReturn(flowOf(DistanceUnit.MI))
        whenever(appPreferences.serverUrl).thenReturn(flowOf("http://10.0.2.2:8000/"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads user`() = runTest(testDispatcher) {
        val user = UserOut(id = "u-1", name = "Alice", email = "alice@example.com")
        whenever(api.getMe()).thenReturn(user)

        viewModel = SettingsViewModel(api, tokenStore, appPreferences)
        advanceTimeBy(200)

        assertIs<UiState.Success<UserOut>>(viewModel.user.value)
        assertEquals(user, (viewModel.user.value as UiState.Success).data)
    }

    @Test
    fun `logout clears token and emits navigateToLogin`() = runTest(testDispatcher) {
        val user = UserOut(id = "u-1", name = "Alice", email = "alice@example.com")
        whenever(api.getMe()).thenReturn(user)

        viewModel = SettingsViewModel(api, tokenStore, appPreferences)
        advanceTimeBy(200)

        val events = mutableListOf<Unit>()
        val job = launch { viewModel.navigateToLogin.collect { events.add(it) } }

        viewModel.logout()
        advanceTimeBy(200)

        verify(tokenStore).clear()
        assertEquals(1, events.size)
        job.cancel()
    }
}
