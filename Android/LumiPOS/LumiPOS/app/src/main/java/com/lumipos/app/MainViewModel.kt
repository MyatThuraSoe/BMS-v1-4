package com.lumipos.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.domain.usecase.auth.GetUsersUseCase
import com.lumipos.domain.usecase.branch.EnsureDefaultBranchUseCase
import com.lumipos.domain.usecase.license.GetLicenseStatusUseCase
import com.lumipos.domain.usecase.session.ObserveSessionUseCase
import com.lumipos.domain.usecase.session.SetSelectedBranchUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed interface StartDestination {
    data object Loading : StartDestination
    data object Activation : StartDestination
    data object Setup : StartDestination
    data object Login : StartDestination
    data object Main : StartDestination
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val getUsersUseCase: GetUsersUseCase,
    private val observeSessionUseCase: ObserveSessionUseCase,
    private val ensureDefaultBranchUseCase: EnsureDefaultBranchUseCase,
    private val setSelectedBranchUseCase: SetSelectedBranchUseCase,
    private val getLicenseStatusUseCase: GetLicenseStatusUseCase
) : ViewModel() {

    private val _startDestination = MutableStateFlow<StartDestination>(StartDestination.Loading)
    val startDestination: StateFlow<StartDestination> = _startDestination

    init {
        viewModelScope.launch {
            combine(
                observeSessionUseCase(),
                getUsersUseCase.getAllUsers().map { it.size },
                getLicenseStatusUseCase()
            ) { session, userCount, license ->
                when {
                    !license.activated -> StartDestination.Activation
                    userCount == 0 -> StartDestination.Setup
                    session.userId == null -> StartDestination.Login
                    else -> StartDestination.Main
                }
            }.collect { destination ->
                _startDestination.value = destination
                if (destination == StartDestination.Main) {
                    ensureBranchSelected()
                }
            }
        }
    }

    private suspend fun ensureBranchSelected() {
        val session = observeSessionUseCase().first()
        if (session.branchId == null) {
            val branchId = ensureDefaultBranchUseCase()
            setSelectedBranchUseCase(branchId)
        }
    }
}