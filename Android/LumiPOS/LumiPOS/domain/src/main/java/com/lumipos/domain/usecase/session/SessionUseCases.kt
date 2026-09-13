package com.lumipos.domain.usecase.session

import com.lumipos.data.preference.SessionState
import com.lumipos.data.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveSessionUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    operator fun invoke(): Flow<SessionState> = sessionRepository.session
}

class SetLoggedInUserUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke(userId: Long) = sessionRepository.saveLoggedInUser(userId)
}

class SetSelectedBranchUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke(branchId: Long) = sessionRepository.setSelectedBranch(branchId)
}

class LogoutUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke() = sessionRepository.clearSession()
}