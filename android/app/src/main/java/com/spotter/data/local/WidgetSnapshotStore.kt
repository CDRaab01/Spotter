package com.spotter.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.widgetSnapshotDataStore by preferencesDataStore(name = "spotter_widget_snapshots")

/**
 * Last-known snapshot of "today's workout" for the home-screen Glance widget, so the widget renders
 * without doing any I/O of its own (Glance can't reach the Room/network layers directly). The app
 * writes the assembled blob whenever the workout/session state changes (see
 * [com.spotter.widget.WidgetUpdater]); the widget reads it via a Hilt EntryPoint. Nothing sensitive
 * lives here — it's the same at-a-glance summary the Home screen already shows — so plain DataStore.
 */
@Singleton
class WidgetSnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun save(key: String, json: String) {
        context.widgetSnapshotDataStore.edit { it[stringPreferencesKey(key)] = json }
    }

    suspend fun read(key: String): String? =
        context.widgetSnapshotDataStore.data.map { it[stringPreferencesKey(key)] }.first()

    companion object {
        const val TODAY = "today"
    }
}
