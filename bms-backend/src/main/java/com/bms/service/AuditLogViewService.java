package com.bms.service;

import com.bms.entity.AuditLog;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.AuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class AuditLogViewService {

    @Autowired
    private AuditLogRepository auditLogRepository;

    public Page<AuditLog> getAllAuditLogs(Pageable pageable) {
        return auditLogRepository.findAll(pageable);
    }

    public Page<AuditLog> getAuditLogsByUserId(Long userId, Pageable pageable) {
        return auditLogRepository.findByUserId(userId, pageable);
    }

    public Page<AuditLog> getAuditLogsByAction(String action, Pageable pageable) {
        return auditLogRepository.findByActionContainingIgnoreCase(action, pageable);
    }

    public Page<AuditLog> getAuditLogsByDateRange(LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return auditLogRepository.findByTimestampBetween(startDate, endDate, pageable);
    }

    public Page<AuditLog> getAuditLogsByUserAndDateRange(Long userId, LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return auditLogRepository.findByUserIdAndTimestampBetween(userId, startDate, endDate, pageable);
    }

    public AuditLog getAuditLogById(Long id) {
        return auditLogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Audit log not found: " + id));
    }

    public Page<AuditLog> searchAuditLogs(String keyword, LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        // For simple search, we'll filter by action containing the keyword
        Page<AuditLog> logs = auditLogRepository.findByActionContainingIgnoreCase(keyword, pageable);
        
        if (startDate != null && endDate != null) {
            // Filter results by date range in memory (for simplicity)
            List<AuditLog> filtered = logs.stream()
                .filter(log -> !log.getTimestamp().isBefore(startDate) && 
                              !log.getTimestamp().isAfter(endDate))
                .toList();
            return new PageImpl<>(filtered, pageable, logs.getTotalElements());
        }
        
        return logs;
    }
}
