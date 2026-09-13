package com.lumipos.app.ui.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.data.schema.RoleEntity
import com.lumipos.data.schema.UserEntity
import com.lumipos.domain.usecase.auth.ChangePasswordUseCase
import com.lumipos.domain.usecase.auth.GetRolesUseCase
import com.lumipos.domain.usecase.auth.GetUsersUseCase
import com.lumipos.domain.usecase.auth.ManageUserUseCase
import com.lumipos.domain.usecase.auth.RegisterUserUseCase
import com.lumipos.domain.usecase.session.ObserveSessionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UsersUiState(
    val users: List<UserEntity> = emptyList(),
    val roles: List<RoleEntity> = emptyList(),
    val roleById: Map<Long, String> = emptyMap(),
    val isLoading: Boolean = true,
    val showForm: Boolean = false,
    val editingUserId: Long = 0,
    val username: String = "",
    val password: String = "",
    val pin: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val roleId: Long = 0,
    val showChangePasswordDialog: Boolean = false,
    val changePasswordTargetId: Long = 0,
    val newPassword: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val currentUserId: Long = 0,
    val isAdmin: Boolean = false
)

@HiltViewModel
class UsersViewModel @Inject constructor(
    private val getUsersUseCase: GetUsersUseCase,
    private val getRolesUseCase: GetRolesUseCase,
    private val registerUserUseCase: RegisterUserUseCase,
    private val changePasswordUseCase: ChangePasswordUseCase,
    private val manageUserUseCase: ManageUserUseCase,
    private val observeSessionUseCase: ObserveSessionUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(UsersUiState())
    val uiState: StateFlow<UsersUiState> = _uiState

    init {
        viewModelScope.launch {
            val session = observeSessionUseCase().first()
            _uiState.update { it.copy(currentUserId = session.userId ?: 0L) }
        }
        viewModelScope.launch {
            getRolesUseCase.getAllRoles().collect { roles ->
                val roleById = roles.associate { it.id to it.name }
                _uiState.update { it.copy(roles = roles, roleById = roleById) }
            }
        }
        viewModelScope.launch {
            getUsersUseCase.getAllUsers().collect { users ->
                _uiState.update { it.copy(users = users, isLoading = false) }
                val currentlyLoggedIn = _uiState.value.currentUserId
                val currentUser = users.find { it.id == currentlyLoggedIn }
                val isAdmin = currentUser != null && _uiState.value.roleById[currentUser.roleId] == "ROLE_ADMIN"
                _uiState.update { it.copy(isAdmin = isAdmin) }
            }
        }
    }

    fun openAdd() = _uiState.update {
        it.copy(showForm = true, editingUserId = 0, username = "", password = "", pin = "", firstName = "", lastName = "", roleId = it.roles.firstOrNull()?.id ?: 0L)
    }

    fun openEdit(user: UserEntity) = _uiState.update {
        it.copy(showForm = true, editingUserId = user.id, username = user.username, password = "", pin = user.pin.orEmpty(), firstName = user.firstName, lastName = user.lastName, roleId = user.roleId)
    }

    fun dismissForm() = _uiState.update { it.copy(showForm = false, isSubmitting = false) }

    fun onUsernameChange(v: String) = _uiState.update { it.copy(username = v) }
    fun onPasswordChange(v: String) = _uiState.update { it.copy(password = v) }
    fun onPinChange(v: String) = _uiState.update { it.copy(pin = v) }
    fun onFirstNameChange(v: String) = _uiState.update { it.copy(firstName = v) }
    fun onLastNameChange(v: String) = _uiState.update { it.copy(lastName = v) }
    fun onRoleIdChange(id: Long) = _uiState.update { it.copy(roleId = id) }

    fun submitForm() {
        val state = _uiState.value
        if (state.username.isBlank()) {
            _uiState.update { it.copy(error = "Username is required") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }
            try {
                if (state.editingUserId == 0L) {
                    if (state.password.length < 6) {
                        _uiState.update { it.copy(isSubmitting = false, error = "Password must be at least 6 characters") }
                        return@launch
                    }
                    registerUserUseCase(
                        username = state.username,
                        password = state.password,
                        roleId = state.roleId,
                        pin = state.pin,
                        firstName = state.firstName,
                        lastName = state.lastName
                    )
                } else {
                    val existing = getUsersUseCase(state.editingUserId) ?: return@launch
                    manageUserUseCase.updateProfile(
                        existing.copy(
                            username = state.username,
                            pin = state.pin.ifBlank { null },
                            firstName = state.firstName,
                            lastName = state.lastName,
                            roleId = state.roleId
                        ),
                        state.currentUserId
                    )
                }
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        showForm = false,
                        message = if (state.editingUserId == 0L) "User created" else "User updated"
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSubmitting = false, error = e.message) }
            }
        }
    }

    fun openChangePassword(userId: Long) = _uiState.update { it.copy(showChangePasswordDialog = true, changePasswordTargetId = userId, newPassword = "") }
    fun dismissChangePassword() = _uiState.update { it.copy(showChangePasswordDialog = false, isSubmitting = false) }
    fun onNewPasswordChange(v: String) = _uiState.update { it.copy(newPassword = v) }

    fun submitChangePassword() {
        val state = _uiState.value
        if (state.newPassword.length < 6) {
            _uiState.update { it.copy(error = "Password must be at least 6 characters") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }
            try {
                changePasswordUseCase(state.changePasswordTargetId, state.currentUserId, state.newPassword)
                _uiState.update { it.copy(isSubmitting = false, showChangePasswordDialog = false, message = "Password updated") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSubmitting = false, error = e.message) }
            }
        }
    }

    fun toggleActive(user: UserEntity) {
        viewModelScope.launch {
            manageUserUseCase.setActive(user.id, !user.isActive, _uiState.value.currentUserId)
        }
    }

    fun resetLockout(user: UserEntity) {
        viewModelScope.launch {
            manageUserUseCase.resetLockout(user.id, _uiState.value.currentUserId)
        }
    }

    fun deleteUser(user: UserEntity) {
        viewModelScope.launch {
            manageUserUseCase.deleteUser(user.id, _uiState.value.currentUserId)
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }
    fun dismissMessage() = _uiState.update { it.copy(message = null) }
}
