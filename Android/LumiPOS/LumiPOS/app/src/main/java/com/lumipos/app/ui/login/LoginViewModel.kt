package com.lumipos.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.domain.usecase.auth.LoginUserUseCase
import com.lumipos.domain.usecase.session.SetLoggedInUserUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val pin: String = "",
    val isUsingPin: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val loginSuccess: Boolean = false
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val loginUserUseCase: LoginUserUseCase,
    private val setLoggedInUserUseCase: SetLoggedInUserUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState

    fun onUsernameChange(value: String) = _uiState.update { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, error = null) }
    fun onPinChange(value: String) = _uiState.update {
        it.copy(pin = value.take(6).filter { c -> c.isDigit() }, error = null)
    }
    fun toggleLoginMethod() = _uiState.update {
        it.copy(isUsingPin = !it.isUsingPin, error = null, username = "", password = "", pin = "")
    }

    fun login() {
        val state = _uiState.value
        if (state.isUsingPin) {
            if (state.pin.length != 6) {
                _uiState.update { it.copy(error = "PIN must be 6 digits") }
                return
            }
        } else {
            if (state.username.isBlank() || state.password.isBlank()) {
                _uiState.update { it.copy(error = "Username and password are required") }
                return
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val user = if (state.isUsingPin) {
                    loginUserUseCase.loginWithPin(state.pin)
                } else {
                    loginUserUseCase(state.username.trim(), state.password)
                }
                if (user != null) {
                    setLoggedInUserUseCase(user.id)
                    _uiState.update { it.copy(isLoading = false, loginSuccess = true) }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = if (state.isUsingPin) "Invalid PIN" else "Invalid credentials"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Login failed")
                }
            }
        }
    }
}