package com.spotter.util

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "spotter_prefs")

@Singleton
class TokenStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val ACCESS_TOKEN = stringPreferencesKey("access_token")
    private val REFRESH_TOKEN = stringPreferencesKey("refresh_token")

    val accessToken: Flow<String?> = context.dataStore.data.map { it[ACCESS_TOKEN] }
    val refreshToken: Flow<String?> = context.dataStore.data.map { it[REFRESH_TOKEN] }

    suspend fun save(accessToken: String, refreshToken: String) {
        context.dataStore.edit {
            it[ACCESS_TOKEN] = accessToken
            it[REFRESH_TOKEN] = refreshToken
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    suspend fun getUserId(): String? {
        val token = accessToken.firstOrNull() ?: return null
        return try {
            val payload = token.split(".").getOrNull(1) ?: return null
            val padded = payload.padEnd((payload.length + 3) / 4 * 4, '=')
            val json = String(Base64.decode(padded, Base64.URL_SAFE))
            Regex(""""sub"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.getOrNull(1)
        } catch (_: Exception) { null }
    }
}
