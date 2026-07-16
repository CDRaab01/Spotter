package com.spotter.ui.navigation

import androidx.lifecycle.ViewModel
import com.spotter.util.ShortcutBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Thin Hilt bridge exposing the app-scoped [ShortcutBus] to composables (the nav graph and Home).
 * Both obtain their own instance via `hiltViewModel()`; because [ShortcutBus] is a `@Singleton`
 * they share the same pending target and consumption is coordinated through it.
 */
@HiltViewModel
class ShortcutViewModel @Inject constructor(
    private val bus: ShortcutBus,
) : ViewModel() {
    val pending: StateFlow<String?> = bus.pending

    fun consume(target: String) = bus.consume(target)
}
