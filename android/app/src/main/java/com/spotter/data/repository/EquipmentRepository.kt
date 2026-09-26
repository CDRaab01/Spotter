package com.spotter.data.repository

import com.spotter.data.model.EquipmentInventory
import com.spotter.data.model.EquipmentOut
import com.spotter.data.remote.ApiService
import com.spotter.util.AppPreferences
import com.spotter.util.Loading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject

/**
 * The user's equipment inventory — the bars, plate pairs, dumbbells and stack step that every
 * suggested load is snapped to (server `app/loading.py`; client [Loading] for the plate
 * calculator and warm-ups).
 *
 * Same shape as [ProfileRepository]: **the server is the source of truth**, [AppPreferences]
 * holds the offline mirror, writes go mirror-first then server, **`IOException` queues locally,
 * `retrofit2.HttpException` surfaces**. The one difference is that the queue remembers *which*
 * write is pending — an edit (`PUT`) or a reset to the default (`DELETE`).
 */
class EquipmentRepository @Inject constructor(
    private val api: ApiService,
    private val appPreferences: AppPreferences,
) {
    /** The mirror. Before the first pull on this device: unconfigured, standard-gym default. */
    val equipment: Flow<EquipmentOut> = appPreferences.equipmentJson.map(::decode)

    suspend fun current(): EquipmentOut = equipment.first()

    /**
     * Pulls the server inventory into the mirror, draining a queued offline write first (it is
     * newer than anything the server can return). Never throws; offline it leaves the mirror alone.
     *
     * @return true when the server was reached and the mirror now matches it.
     */
    suspend fun refresh(): Boolean {
        if (!drainPending()) return false
        return try {
            mirror(api.getEquipment())
            true
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Saves an edited inventory: the mirror immediately, then the server.
     *
     * @return true when the server acknowledged it, false when it was queued for the next drain.
     * @throws retrofit2.HttpException so the caller can say the save didn't land.
     */
    suspend fun save(inventory: EquipmentInventory): Boolean {
        mirror(EquipmentOut(configured = true, inventory = inventory))
        return try {
            mirror(api.putEquipment(inventory))
            appPreferences.setEquipmentSyncPending(NONE)
            true
        } catch (_: IOException) {
            appPreferences.setEquipmentSyncPending(PUT)
            false
        }
    }

    /** Forgets the saved inventory (back to the standard-gym default). Same contract as [save]. */
    suspend fun reset(): Boolean {
        mirror(EquipmentOut(configured = false, inventory = Loading.DEFAULT_LB))
        return try {
            mirror(api.resetEquipment())
            appPreferences.setEquipmentSyncPending(NONE)
            true
        } catch (_: IOException) {
            appPreferences.setEquipmentSyncPending(DELETE)
            false
        }
    }

    /** Pending-drain entry point for the reconnect observer / Home sync round; never throws. */
    suspend fun syncPending(): Boolean = drainPending()

    private suspend fun drainPending(): Boolean {
        val pending = appPreferences.equipmentSyncPending.first()
        if (pending == NONE) return true
        return try {
            mirror(
                if (pending == DELETE) api.resetEquipment()
                else api.putEquipment(current().inventory),
            )
            appPreferences.setEquipmentSyncPending(NONE)
            true
        } catch (_: IOException) {
            false // still offline — keep it queued
        } catch (_: Exception) {
            // The server answered with an error, so the write is undeliverable as-is. Drop it rather
            // than retrying forever; the mirror keeps the user's values until a later pull.
            appPreferences.setEquipmentSyncPending(NONE)
            false
        }
    }

    private suspend fun mirror(out: EquipmentOut) {
        appPreferences.setEquipmentJson(json.encodeToString(EquipmentOut.serializer(), out))
    }

    companion object {
        private const val NONE = ""
        private const val PUT = "put"
        private const val DELETE = "delete"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        internal fun decode(raw: String?): EquipmentOut =
            raw?.let { runCatching { json.decodeFromString(EquipmentOut.serializer(), it) }.getOrNull() }
                ?: EquipmentOut(configured = false, inventory = Loading.DEFAULT_LB)

        /**
         * The inventory the phone should do its own arithmetic with. Unconfigured, it is the
         * standard gym *in the user's display unit* — a kg user shouldn't be shown lb plates.
         */
        fun effectiveInventory(out: EquipmentOut, displayUnit: String): EquipmentInventory =
            if (out.configured) out.inventory else Loading.defaultFor(displayUnit)
    }
}
