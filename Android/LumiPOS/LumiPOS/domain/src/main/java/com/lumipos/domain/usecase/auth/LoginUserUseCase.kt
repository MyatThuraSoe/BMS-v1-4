package com.lumipos.domain.usecase.auth

import com.lumipos.data.repository.UsersRepository
import com.lumipos.data.schema.UserEntity
import javax.inject.Inject

class LoginUserUseCase @Inject constructor(
    private val usersRepository: UsersRepository
) {
    suspend operator fun invoke(username: String, password: String): UserEntity? {
        return usersRepository.authenticate(username, password)
    }

    suspend fun loginWithPin(pin: String): UserEntity? {
        return usersRepository.getAllActiveUsers()
            .let { usersRepository.getUsersOnce() }
            .firstOrNull { it.pin == pin }
    }
}