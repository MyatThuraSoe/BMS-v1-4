package com.lumipos.domain.usecase.auth

import com.lumipos.data.repository.UsersRepository
import com.lumipos.data.schema.UserEntity
import com.lumipos.data.repository.impl.UsersRepositoryImpl
import javax.inject.Inject

class RegisterUserUseCase @Inject constructor(
    private val usersRepository: UsersRepository
) {
    suspend operator fun invoke(
        username: String,
        password: String,
        roleId: Long,
        pin: String,
        firstName: String,
        lastName: String
    ): Long {
        val user = UserEntity(
            username = username,
            passwordHash = UsersRepositoryImpl.hashPassword(password),
            roleId = roleId,
            pin = pin,
            firstName = firstName,
            lastName = lastName,
            isActive = true
        )
        return usersRepository.insertUser(user)
    }
}