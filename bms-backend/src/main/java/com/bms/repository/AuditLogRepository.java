package com.bms.repository;

import com.bms.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    Page<AuditLog> findByUserId(Long userId, Pageable pageable);
    
    Page<AuditLog> findByActionContainingIgnoreCase(String action, Pageable pageable);
    
    @Query("SELECT al FROM AuditLog al WHERE al.timestamp BETWEEN :startDate AND :endDate")
    Page<AuditLog> findByTimestampBetween(@Param("startDate") LocalDateTime startDate, 
                                         @Param("endDate") LocalDateTime endDate, 
                                         Pageable pageable);
    
    @Query("SELECT al FROM AuditLog al WHERE al.userId = :userId AND al.timestamp BETWEEN :startDate AND :endDate")
    Page<AuditLog> findByUserIdAndTimestampBetween(@Param("userId") Long userId,
                                                   @Param("startDate") LocalDateTime startDate,
                                                   @Param("endDate") LocalDateTime endDate,
                                                   Pageable pageable);

    @Query("""
        SELECT al FROM AuditLog al
        WHERE (:userId IS NULL OR al.userId = :userId)
          AND (:action IS NULL OR LOWER(al.action) LIKE LOWER(CONCAT('%', :action, '%')))
          AND (:startDate IS NULL OR al.timestamp >= :startDate)
          AND (:endDate IS NULL OR al.timestamp < :endDate)
        """)
    Page<AuditLog> findFiltered(@Param("userId") Long userId,
                                @Param("action") String action,
                                @Param("startDate") LocalDateTime startDate,
                                @Param("endDate") LocalDateTime endDate,
                                Pageable pageable);
}
