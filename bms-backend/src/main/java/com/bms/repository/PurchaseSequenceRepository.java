package com.bms.repository;

import com.bms.entity.PurchaseSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface PurchaseSequenceRepository
        extends JpaRepository<PurchaseSequence, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT p 
        FROM PurchaseSequence p
        WHERE p.sequenceDate = :date
    """)
    Optional<PurchaseSequence> findByDateForUpdate(
            @Param("date") LocalDate date
    );
}