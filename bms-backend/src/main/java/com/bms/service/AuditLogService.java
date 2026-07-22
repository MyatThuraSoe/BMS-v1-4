package com.bms.service;

import com.bms.entity.AuditLog;
import com.bms.repository.AuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AuditLogService {

    @Autowired
    private AuditLogRepository auditLogRepository;

    public void logAction(Long userId, String action, String description, String entityType, Long entityId, String oldValues, String newValues) {
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(userId);
        auditLog.setAction(action);
        auditLog.setDescription(description);
        auditLog.setEntityType(entityType);
        auditLog.setEntityId(entityId);
        auditLog.setOldValues(oldValues);
        auditLog.setNewValues(newValues);
        auditLog.setTimestamp(LocalDateTime.now());

        auditLogRepository.save(auditLog);
    }
}
