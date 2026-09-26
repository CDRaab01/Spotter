package com.spotter.data.repository

import com.spotter.data.model.EquipmentInventory
import com.spotter.data.model.EquipmentOut
import com.spotter.data.model.PlatePair
import com.spotter.data.remote.ApiService
import com.spotter.util.AppPreferences
import com.spotter.util.Loading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
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

/** Same contract as the training profile: queued offline, never dropped, never lies about an HTTP error. */
@OptIn(ExperimentalCoroutinesApi::class)
class EquipmentRepositoryTest {

    private lateinit var api: ApiService
    private lateinit var prefs: FakeEquipmentPreferences
    private lateinit var repo: EquipmentRepository

    private val homeGym = EquipmentInventory(
        unit = "lb",
        bars = listOf(45.0),
        plates = listOf(PlatePair(45.0, 2), PlatePair(2.5, 1)),
        dumbbells = listOf(10.0, 20.0),
    )

    @Before
    fun setup() {
        api = mock()
        prefs = FakeEquipmentPreferences()
        repo = EquipmentRepository(api, prefs.instance)
    }

    @Test
    fun `before any pull the mirror is the unconfigured standard gym`() = runTest {
        assertEquals(EquipmentOut(configured = false, inventory = Loading.DEFAULT_LB), repo.current())
    }

    @Test
    fun `save writes the mirror and pushes`() = runTest {
        whenever(api.putEquipment(any())).thenReturn(EquipmentOut(true, homeGym))

        assertTrue(repo.save(homeGym))

        assertEquals(EquipmentOut(true, homeGym), repo.current())
        assertEquals("", prefs.pending.value)
        verify(api).putEquipment(homeGym)
    }

    @Test
    fun `save offline keeps the edit and queues it`() = runTest {
        whenever(api.putEquipment(any())).thenAnswer { throw IOException("offline") }

        assertFalse(repo.save(homeGym))

        assertEquals(EquipmentOut(true, homeGym), repo.current())
        assertEquals("put", prefs.pending.value)
    }

    @Test
    fun `save surfaces an http error`() = runTest {
        whenever(api.putEquipment(any())).thenAnswer { throw httpException(422) }
        assertFailsWith<HttpException> { repo.save(homeGym) }
    }

    @Test
    fun `refresh drains a queued edit before pulling`() = runTest {
        prefs.pending.value = "put"
        whenever(api.putEquipment(any())).thenAnswer { throw IOException("offline") }
        repo.save(homeGym)
        // Re-stub with doReturn: whenever(api.putEquipment(..)) would *call* the throwing stub.
        doReturn(EquipmentOut(true, homeGym)).whenever(api).putEquipment(any())
        whenever(api.getEquipment()).thenReturn(EquipmentOut(true, homeGym))

        assertTrue(repo.refresh())

        assertEquals("", prefs.pending.value)
        assertEquals(EquipmentOut(true, homeGym), repo.current())
    }

    @Test
    fun `refresh with an undeliverable edit does not pull over it`() = runTest {
        prefs.pending.value = "put"
        whenever(api.putEquipment(any())).thenAnswer { throw IOException("offline") }

        assertFalse(repo.refresh())
        verify(api, never()).getEquipment()
        assertEquals("put", prefs.pending.value)
    }

    @Test
    fun `an offline reset is queued as a delete and drained as one`() = runTest {
        whenever(api.resetEquipment()).thenAnswer { throw IOException("offline") }
        assertFalse(repo.reset())
        assertEquals("delete", prefs.pending.value)
        assertFalse(repo.current().configured)

        doReturn(EquipmentOut(false, Loading.DEFAULT_LB)).whenever(api).resetEquipment()
        assertTrue(repo.syncPending())
        verify(api, never()).putEquipment(any())
        assertEquals("", prefs.pending.value)
    }

    @Test
    fun `a kg user who never set equipment computes with kg plates`() {
        val unset = EquipmentOut(configured = false, inventory = Loading.DEFAULT_LB)
        assertEquals(Loading.DEFAULT_KG, EquipmentRepository.effectiveInventory(unset, "kg"))
        assertEquals(homeGym, EquipmentRepository.effectiveInventory(EquipmentOut(true, homeGym), "kg"))
    }

    private fun httpException(code: Int) = HttpException(
        Response.error<Unit>(code, "".toResponseBody("application/json".toMediaType())),
    )
}

private class FakeEquipmentPreferences {
    val json = MutableStateFlow<String?>(null)
    val pending = MutableStateFlow("")

    val instance: AppPreferences = mock<AppPreferences>().also { prefs ->
        whenever(prefs.equipmentJson).thenReturn(json)
        whenever(prefs.equipmentSyncPending).thenReturn(pending)
        org.mockito.kotlin.wheneverBlocking { prefs.setEquipmentJson(any()) }.thenAnswer { invocation ->
            json.value = invocation.getArgument(0)
            Unit
        }
        org.mockito.kotlin.wheneverBlocking { prefs.setEquipmentSyncPending(any()) }
            .thenAnswer { invocation ->
                pending.value = invocation.getArgument(0)
                Unit
            }
    }
}
