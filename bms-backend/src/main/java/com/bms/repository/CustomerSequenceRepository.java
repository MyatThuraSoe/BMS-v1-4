package com.bms.repository;

import com.bms.entity.CustomerSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface CustomerSequenceRepository extends JpaRepository<CustomerSequence, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM CustomerSequence s WHERE s.sequenceDate = :date")
    Optional<CustomerSequence> findByDateForUpdate(@Param("date") LocalDate date);
}
