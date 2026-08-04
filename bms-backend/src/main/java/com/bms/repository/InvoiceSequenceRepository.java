package com.bms.repository;

import com.bms.entity.InvoiceSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface InvoiceSequenceRepository
        extends JpaRepository<InvoiceSequence, Long> {


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT i 
        FROM InvoiceSequence i
        WHERE i.sequenceDate = :date
    """)
    Optional<InvoiceSequence> findByDateForUpdate(
            @Param("date") LocalDate date
    );
}