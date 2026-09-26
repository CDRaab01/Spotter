package com.spotter.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.EquipmentInventory
import com.spotter.data.model.EquipmentOut
import com.spotter.data.repository.EquipmentRepository
import com.spotter.util.AppPreferences
import com.spotter.util.Loading
import com.spotter.util.WeightUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EquipmentUiState(
    val loading: Boolean = true,
    /** False until the user saves: the draft is then the standard gym in their display unit. */
    val configured: Boolean = false,
    val draft: EquipmentInventory = Loading.DEFAULT_LB,
    val dirty: Boolean = false,
    val saving: Boolean = false,
)

/**
 * The equipment editor: a draft of the whole inventory, saved as one PUT (the server rate-limits
 * writes, so per-tap autosave would be wrong here). Mirror first so it opens instantly offline,
 * then a refresh that only replaces the draft if the user hasn't started editing.
 */
@HiltViewModel
class EquipmentViewModel @Inject constructor(
    private val repository: EquipmentRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(EquipmentUiState())
    val state: StateFlow<EquipmentUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            val unit = if (appPreferences.weightUnit.first() == WeightUnit.KG) "kg" else "lb"
            load(repository.current(), unit)
            if (runCatching { repository.refresh() }.getOrDefault(false) && !_state.value.dirty) {
                load(repository.current(), unit)
            }
        }
    }

    private fun load(out: EquipmentOut, displayUnit: String) {
        _state.update {
            it.copy(
                loading = false,
                configured = out.configured,
                draft = EquipmentRepository.effectiveInventory(out, displayUnit),
                dirty = false,
            )
        }
    }

    private fun edit(transform: (EquipmentInventory) -> EquipmentInventory) {
        _state.update { it.copy(draft = transform(it.draft), dirty = true) }
    }

    /** Plates are stamped in one unit, so switching starts from that unit's standard set. */
    fun setUnit(unit: String) {
        if (unit == _state.value.draft.unit) return
        edit { Loading.defaultFor(unit) }
    }

    fun toggleBar(weight: Double) = edit { inv ->
        val bars = if (weight in inv.bars) inv.bars - weight else inv.bars + weight
        inv.copy(bars = bars.sortedDescending())
    }

    fun setPlatePairs(weight: Double, pairs: Int) = edit {
        EquipmentOptions.withPlatePairs(it, weight, pairs.coerceIn(0, MAX_PAIRS))
    }

    fun toggleDumbbell(weight: Double) = edit { inv ->
        val next = if (weight in inv.dumbbells) inv.dumbbells - weight else inv.dumbbells + weight
        inv.copy(dumbbells = next.sorted())
    }

    fun setDumbbells(weights: List<Double>) = edit { it.copy(dumbbells = weights.distinct().sorted()) }

    /** Null = no machines/cables. */
    fun setStackStep(step: Double?) = edit { it.copy(stackStep = step) }

    fun save() {
        if (_state.value.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            try {
                val pushed = repository.save(_state.value.draft)
                _state.update { it.copy(configured = true, dirty = false) }
                _messages.emit(
                    if (pushed) "Equipment saved"
                    else "Saved on this device — it'll sync when you're back online.",
                )
            } catch (_: Exception) {
                _messages.emit("Couldn't save your equipment. Try again.")
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    /** Forget the saved inventory; suggestions go back to assuming a standard gym. */
    fun resetToDefault() {
        if (_state.value.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            try {
                repository.reset()
                _state.update {
                    it.copy(
                        configured = false,
                        draft = Loading.defaultFor(it.draft.unit),
                        dirty = false,
                    )
                }
                _messages.emit("Back to a standard gym")
            } catch (_: Exception) {
                _messages.emit("Couldn't reset your equipment. Try again.")
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    companion object {
        /** Mirrors the server's `INVENTORY_MAX_PAIRS`. */
        const val MAX_PAIRS = 20
    }
}
