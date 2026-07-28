package com.spotter.health

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin, **write-only** wrapper over the Health Connect SDK: availability, the two write
 * permissions Spotter asks for, and the two record inserts it performs. All time/type/unit
 * decisions live in the pure [HealthMapper]; this class only translates its output to SDK types
 * and talks to the platform.
 *
 * Spotter never *reads* from Health Connect — the app's own server remains the source of truth,
 * and the mirror exists so a finished workout / weigh-in shows up in the user's system health
 * record. Every entry point degrades to a no-op (never throws) when the SDK is missing, the
 * permissions were not granted, or the platform errors: a health-mirror failure must never affect
 * the user's save.
 */
@Singleton
class HealthConnectManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Whether Health Connect can be used on this device — and if not, why. */
    enum class Availability {
        /** Installed and usable. */
        AVAILABLE,

        /** Present but the provider APK is too old; the user must update it. */
        UPDATE_REQUIRED,

        /** No Health Connect on this device (common below API 34 without the app installed). */
        UNAVAILABLE,
    }

    /** The permission set Spotter requests: write exercise sessions + write bodyweight. */
    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
    )

    fun availability(): Availability =
        when (runCatching { HealthConnectClient.getSdkStatus(context) }.getOrNull()) {
            HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.UPDATE_REQUIRED
            else -> Availability.UNAVAILABLE
        }

    val isAvailable: Boolean get() = availability() == Availability.AVAILABLE

    /**
     * The `ActivityResultContract` the Settings screen launches to ask for [permissions]. Safe to
     * build even when Health Connect is absent — launching it there simply returns nothing granted.
     */
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    /** True when every permission in [permissions] is already granted. False if the SDK is absent. */
    suspend fun hasPermissions(): Boolean {
        val client = client() ?: return false
        return runCatching {
            client.permissionController.getGrantedPermissions().containsAll(permissions)
        }.getOrDefault(false)
    }

    /** Insert one exercise session. Returns true when it actually landed. */
    suspend fun writeSession(input: ExerciseSessionInput): Boolean {
        val client = client() ?: return false
        return runCatching {
            client.insertRecords(
                listOf(
                    ExerciseSessionRecord(
                        startTime = input.start,
                        startZoneOffset = null,
                        endTime = input.end,
                        endZoneOffset = null,
                        // Spotter tracked this session live in the app.
                        metadata = Metadata.activelyRecorded(SPOTTER_DEVICE),
                        exerciseType = input.type.toSdkExerciseType(),
                        title = input.title,
                    ),
                ),
            )
            true
        }.getOrDefault(false)
    }

    /** Insert one bodyweight record. Returns true when it actually landed. */
    suspend fun writeWeight(input: WeightInput): Boolean {
        val client = client() ?: return false
        return runCatching {
            client.insertRecords(
                listOf(
                    WeightRecord(
                        time = input.time,
                        zoneOffset = null,
                        weight = Mass.pounds(input.pounds),
                        // A weigh-in is typed in by hand, not measured by the phone.
                        metadata = Metadata.manualEntry(SPOTTER_DEVICE),
                    ),
                ),
            )
            true
        }.getOrDefault(false)
    }

    /** The client, or null when Health Connect isn't usable here (never throws). */
    private fun client(): HealthConnectClient? =
        if (!isAvailable) null
        else runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()

    private fun HealthExerciseType.toSdkExerciseType(): Int = when (this) {
        HealthExerciseType.STRENGTH_TRAINING -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        HealthExerciseType.RUNNING -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        HealthExerciseType.WALKING -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
    }

    private companion object {
        /** Records are attributed to the phone Spotter runs on. */
        val SPOTTER_DEVICE = Device(type = Device.TYPE_PHONE)
    }
}
