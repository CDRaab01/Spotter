package com.spotter.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A tiny app-scoped bus for notification deep-links. [MainActivity] emits a [DeepLinkTarget] when
 * launched/re-launched from a notification; the nav graph collects [targets] and navigates. Kept
 * separate from any ViewModel so the signal survives Activity/VM recreation.
 */
@Singleton
class DeepLinkBus @Inject constructor() {
    private val _targets = MutableSharedFlow<DeepLinkTarget>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val targets: SharedFlow<DeepLinkTarget> = _targets.asSharedFlow()

    fun emit(target: DeepLinkTarget) {
        _targets.tryEmit(target)
    }
}
