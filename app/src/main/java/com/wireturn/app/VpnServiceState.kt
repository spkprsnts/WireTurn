package com.wireturn.app

import com.wireturn.app.viewmodel.VpnState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object VpnServiceState {
    private val _state = MutableStateFlow<VpnState>(VpnState.Idle)
    val state = _state.asStateFlow()

    fun updateStatus(newStatus: VpnState) {
        _state.value = newStatus
    }
}

