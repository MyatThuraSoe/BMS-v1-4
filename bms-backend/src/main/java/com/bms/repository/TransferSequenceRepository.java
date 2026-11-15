package com.bms.repository;

import com.bms.entity.TransferSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface TransferSequenceRepository extends JpaRepository<TransferSequence, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM TransferSequence s WHERE s.sequenceDate = :date")
    Optional<TransferSequence> findByDateForUpdate(@Param("date") LocalDate date);
}
