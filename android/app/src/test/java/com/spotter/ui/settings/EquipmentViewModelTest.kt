package com.spotter.ui.settings

import com.spotter.data.model.EquipmentInventory
import com.spotter.data.model.EquipmentOut
import com.spotter.data.model.PlatePair
import com.spotter.data.repository.EquipmentRepository
import com.spotter.util.AppPreferences
import com.spotter.util.Loading
import com.spotter.util.WeightUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EquipmentViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: EquipmentRepository
    private lateinit var prefs: AppPreferences

    private val saved = EquipmentInventory(
        unit = "lb",
        bars = listOf(45.0),
        plates = listOf(PlatePair(45.0, 2), PlatePair(5.0, 1)),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        repository = mock()
        prefs = mock()
        whenever(prefs.weightUnit).thenReturn(flowOf(WeightUnit.LBS))
        wheneverBlocking { repository.refresh() }.thenReturn(false)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `opens on the saved inventory`() = runTest(dispatcher) {
        wheneverBlocking { repository.current() }.thenReturn(EquipmentOut(true, saved))
        val vm = EquipmentViewModel(repository, prefs)
        advanceUntilIdle()

        assertTrue(vm.state.value.configured)
        assertEquals(saved, vm.state.value.draft)
        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun `an unset kg user starts from kg plates`() = runTest(dispatcher) {
        whenever(prefs.weightUnit).thenReturn(flowOf(WeightUnit.KG))
        wheneverBlocking { repository.current() }.thenReturn(EquipmentOut(false, Loading.DEFAULT_LB))
        val vm = EquipmentViewModel(repository, prefs)
        advanceUntilIdle()

        assertFalse(vm.state.value.configured)
        assertEquals(Loading.DEFAULT_KG, vm.state.value.draft)
    }

    @Test
    fun `edits mark the draft dirty and switching unit starts from that unit's set`() = runTest(dispatcher) {
        wheneverBlocking { repository.current() }.thenReturn(EquipmentOut(true, saved))
        val vm = EquipmentViewModel(repository, prefs)
        advanceUntilIdle()

        vm.setPlatePairs(2.5, 2)
        assertTrue(vm.state.value.dirty)
        assertEquals(2, EquipmentOptions.pairsFor(vm.state.value.draft, 2.5))

        vm.toggleBar(25.0)
        assertEquals(listOf(45.0, 25.0), vm.state.value.draft.bars)

        vm.setUnit("kg")
        assertEquals(Loading.DEFAULT_KG, vm.state.value.draft)
    }

    @Test
    fun `save pushes the draft and clears dirty`() = runTest(dispatcher) {
        wheneverBlocking { repository.current() }.thenReturn(EquipmentOut(true, saved))
        wheneverBlocking { repository.save(any()) }.thenReturn(true)
        val vm = EquipmentViewModel(repository, prefs)
        advanceUntilIdle()

        vm.setDumbbells(listOf(20.0, 10.0))
        vm.save()
        advanceUntilIdle()

        verify(repository).save(saved.copy(dumbbells = listOf(10.0, 20.0)))
        assertFalse(vm.state.value.dirty)
        assertTrue(vm.state.value.configured)
    }

    @Test
    fun `a slow refresh never overwrites an edit in progress`() = runTest(dispatcher) {
        val server = saved.copy(bars = listOf(20.0))
        wheneverBlocking { repository.current() }
            .thenReturn(EquipmentOut(true, saved), EquipmentOut(true, server))
        wheneverBlocking { repository.refresh() }.doSuspendableAnswer {
            delay(1_000)
            true
        }
        val vm = EquipmentViewModel(repository, prefs)
        advanceTimeBy(100) // mirror loaded, refresh still in flight
        vm.setStackStep(10.0)
        advanceUntilIdle()

        assertEquals(10.0, vm.state.value.draft.stackStep)
        assertEquals(listOf(45.0), vm.state.value.draft.bars)
        verify(repository, never()).save(any())
    }
}
