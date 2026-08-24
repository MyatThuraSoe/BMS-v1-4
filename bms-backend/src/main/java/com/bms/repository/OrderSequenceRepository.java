package com.bms.repository;

import com.bms.entity.OrderSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface OrderSequenceRepository
        extends JpaRepository<OrderSequence, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OrderSequence o WHERE o.sequenceDate = :date")
    Optional<OrderSequence> findByDateForUpdate(@Param("date") LocalDate date);
}