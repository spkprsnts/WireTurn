package com.wireturn.app

import com.wireturn.app.viewmodel.VpnState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object VpnServiceState {
    private val _state = MutableStateFlow<VpnState>(VpnState.Idle)
    val state = _state.asStateFlow()

    private val _wasManuallyDisabled = MutableStateFlow(false)
    val wasManuallyDisabled = _wasManuallyDisabled.asStateFlow()

    fun updateStatus(newStatus: VpnState) {
        _state.value = newStatus
        if (newStatus is VpnState.Running) {
            _wasManuallyDisabled.value = false
        }
    }

    fun setManuallyDisabled(disabled: Boolean) {
        _wasManuallyDisabled.value = disabled
    }
}

