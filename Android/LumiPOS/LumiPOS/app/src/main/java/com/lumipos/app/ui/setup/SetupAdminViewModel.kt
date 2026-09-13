package com.lumipos.app.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.domain.usecase.auth.SetupAdminUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupAdminUiState(
    val username: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val pin: String = "",
    val confirmPin: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val success: Boolean = false
)

@HiltViewModel
class SetupAdminViewModel @Inject constructor(
    private val setupAdminUseCase: SetupAdminUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupAdminUiState())
    val uiState: StateFlow<SetupAdminUiState> = _uiState

    fun onUsernameChange(value: String) = _uiState.update { it.copy(username = value) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value) }
    fun onConfirmPasswordChange(value: String) = _uiState.update { it.copy(confirmPassword = value) }
    fun onPinChange(value: String) =
        _uiState.update { it.copy(pin = value.take(6).filter { c -> c.isDigit() }) }
    fun onConfirmPinChange(value: String) =
        _uiState.update { it.copy(confirmPin = value.take(6).filter { c -> c.isDigit() }) }

    fun submitSetup() {
        val state = _uiState.value
        val validationError = validate(state)
        if (validationError != null) {
            _uiState.update { it.copy(error = validationError) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                setupAdminUseCase(
                    username = state.username.trim(),
                    password = state.password,
                    pin = state.pin
                )
                _uiState.update { it.copy(isLoading = false, success = true) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Setup failed")
                }
            }
        }
    }

    private fun validate(state: SetupAdminUiState): String? {
        if (state.username.isBlank()) return "Username is required"
        if (state.password.length < 4) return "Password must be at least 4 characters"
        if (state.password != state.confirmPassword) return "Passwords do not match"
        if (state.pin.length != 6) return "PIN must be 6 digits"
        if (state.pin != state.confirmPin) return "PINs do not match"
        return null
    }
}