package com.lumipos.domain.usecase.auth

import com.lumipos.data.repository.RolesRepository
import com.lumipos.data.repository.UsersRepository
import com.lumipos.data.schema.UserEntity
import com.lumipos.data.repository.impl.UsersRepositoryImpl
import javax.inject.Inject

class SetupAdminUseCase @Inject constructor(
    private val usersRepository: UsersRepository,
    private val rolesRepository: RolesRepository
) {
    suspend operator fun invoke(username: String, password: String, pin: String): UserEntity {
        rolesRepository.seedRoles()
        val adminRole = rolesRepository.getRoleByName("ROLE_ADMIN")
            ?: error("Admin role not found")
        val existingCount = usersRepository.getUserCount()
        if (existingCount > 0) {
            error("Admin already set up")
        }
        val user = UserEntity(
            username = username,
            passwordHash = UsersRepositoryImpl.hashPassword(password),
            roleId = adminRole.id,
            pin = pin,
            firstName = "Admin",
            isActive = true
        )
        val id = usersRepository.insertUser(user)
        return user.copy(id = id)
    }
}