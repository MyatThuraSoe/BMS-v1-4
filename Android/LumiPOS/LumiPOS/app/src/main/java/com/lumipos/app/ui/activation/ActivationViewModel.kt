package com.lumipos.app.ui.activation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.domain.usecase.license.ActivateLicenseUseCase
import com.lumipos.domain.usecase.license.GetMachineIdUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ActivationUiState(
    val machineId: String = "",
    val licenseKey: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val activated: Boolean = false,
    val statusMessage: String? = null
)

@HiltViewModel
class ActivationViewModel @Inject constructor(
    private val getMachineIdUseCase: GetMachineIdUseCase,
    private val activateLicenseUseCase: ActivateLicenseUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ActivationUiState())
    val uiState: StateFlow<ActivationUiState> = _uiState

    init {
        _uiState.update { it.copy(machineId = getMachineIdUseCase()) }
    }

    fun onLicenseKeyChange(value: String) = _uiState.update { it.copy(licenseKey = value, error = null) }

    fun activate() {
        val key = _uiState.value.licenseKey.trim()
        if (key.isBlank()) {
            _uiState.update { it.copy(error = "Enter a license key.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = activateLicenseUseCase(key)
            _uiState.update {
                if (result.success) {
                    it.copy(
                        isLoading = false,
                        licenseKey = "",
                        activated = true,
                        statusMessage = result.message
                    )
                } else {
                    it.copy(isLoading = false, error = result.message)
                }
            }
        }
    }
}