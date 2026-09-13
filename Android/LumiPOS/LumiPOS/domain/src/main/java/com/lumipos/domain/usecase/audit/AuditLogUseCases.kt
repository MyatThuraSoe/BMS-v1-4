package com.lumipos.domain.usecase.audit

import com.lumipos.data.repository.AuditLogsRepository
import com.lumipos.data.schema.AuditLogEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetAuditLogsUseCase @Inject constructor(
    private val auditLogsRepository: AuditLogsRepository
) {
    fun getAllLogs(): Flow<List<AuditLogEntity>> = auditLogsRepository.getAllLogs()

    fun getLogsByDateRange(startTime: Long, endTime: Long): Flow<List<AuditLogEntity>> =
        auditLogsRepository.getLogsByDateRange(startTime, endTime)

    fun getLogsByAction(action: String): Flow<List<AuditLogEntity>> = auditLogsRepository.getLogsByAction(action)
}