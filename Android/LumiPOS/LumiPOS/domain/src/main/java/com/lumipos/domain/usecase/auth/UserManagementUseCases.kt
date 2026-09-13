package com.lumipos.domain.usecase.auth

import com.lumipos.data.repository.AuditLogsRepository
import com.lumipos.data.repository.RolesRepository
import com.lumipos.data.repository.UsersRepository
import com.lumipos.data.repository.impl.UsersRepositoryImpl
import com.lumipos.data.schema.AuditLogEntity
import com.lumipos.data.schema.UserEntity
import javax.inject.Inject

class GetRolesUseCase @Inject constructor(
    private val rolesRepository: RolesRepository
) {
    fun getAllRoles() = rolesRepository.getAllRoles()
}

class ChangePasswordUseCase @Inject constructor(
    private val usersRepository: UsersRepository,
    private val auditLogsRepository: AuditLogsRepository
) {
    suspend operator fun invoke(userId: Long, currentUserId: Long, newPassword: String) {
        val hash = UsersRepositoryImpl.hashPassword(newPassword)
        usersRepository.changePassword(userId, hash)
        auditLogsRepository.insertLog(
            AuditLogEntity(
                userId = currentUserId,
                action = "USER_PASSWORD_CHANGE",
                entityType = "USER",
                entityId = userId,
                detail = "Password changed"
            )
        )
    }
}

class ManageUserUseCase @Inject constructor(
    private val usersRepository: UsersRepository,
    private val auditLogsRepository: AuditLogsRepository
) {
    suspend fun updateProfile(user: UserEntity, performedBy: Long?) {
        usersRepository.updateUser(user)
        auditLogsRepository.insertLog(
            AuditLogEntity(userId = performedBy, action = "USER_UPDATE", entityType = "USER", entityId = user.id, detail = "Profile updated for ${user.username}")
        )
    }

    suspend fun updateRole(userId: Long, roleId: Long, performedBy: Long?) {
        val user = usersRepository.getUserById(userId) ?: return
        usersRepository.updateUser(user.copy(roleId = roleId))
        auditLogsRepository.insertLog(
            AuditLogEntity(userId = performedBy, action = "USER_ROLE_CHANGE", entityType = "USER", entityId = userId, detail = "Role changed to $roleId")
        )
    }

    suspend fun setActive(userId: Long, active: Boolean, performedBy: Long?) {
        val user = usersRepository.getUserById(userId) ?: return
        usersRepository.updateUser(user.copy(isActive = active))
        auditLogsRepository.insertLog(
            AuditLogEntity(userId = performedBy, action = if (active) "USER_ENABLE" else "USER_DISABLE", entityType = "USER", entityId = userId, detail = if (active) "Enabled" else "Disabled")
        )
    }

    suspend fun resetLockout(userId: Long, performedBy: Long?) {
        usersRepository.resetLockout(userId)
        auditLogsRepository.insertLog(
            AuditLogEntity(userId = performedBy, action = "USER_LOCKOUT_RESET", entityType = "USER", entityId = userId, detail = "Lockout reset")
        )
    }

    suspend fun deleteUser(userId: Long, performedBy: Long?) {
        usersRepository.deleteUserById(userId)
        auditLogsRepository.insertLog(
            AuditLogEntity(userId = performedBy, action = "USER_DELETE", entityType = "USER", entityId = userId, detail = "User deleted")
        )
    }
}
