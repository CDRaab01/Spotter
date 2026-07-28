package com.spotter.ui.cardio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.local.dao.CardioSessionDao
import com.spotter.data.local.entity.CardioSessionEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Recent completed cardio for the Cardio home's history list. Before this, a finished or
 * manually logged run only existed as a number inside the streak/active-minutes stats —
 * there was no surface anywhere that listed it back.
 */
@HiltViewModel
class CardioHomeViewModel @Inject constructor(
    cardioSessionDao: CardioSessionDao,
) : ViewModel() {

    val recentSessions: StateFlow<List<CardioSessionEntity>> = cardioSessionDao.observeAll()
        .map { sessions ->
            sessions
                .filter { it.status == "completed" }
                .sortedByDescending { it.completedAt ?: it.startedAt }
                .take(15)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
