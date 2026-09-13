package com.lumipos.domain.usecase.auth

import com.lumipos.data.repository.UsersRepository
import com.lumipos.data.schema.UserEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetUsersUseCase @Inject constructor(
    private val usersRepository: UsersRepository
) {
    fun getAllUsers(): Flow<List<UserEntity>> = usersRepository.getAllUsers()

    fun getAllActiveUsers(): Flow<List<UserEntity>> = usersRepository.getAllActiveUsers()

    suspend operator fun invoke(id: Long): UserEntity? = usersRepository.getUserById(id)

    suspend fun getUserCount(): Int = usersRepository.getUserCount()
}