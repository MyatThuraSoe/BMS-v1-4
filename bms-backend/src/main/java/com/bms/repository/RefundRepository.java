package com.bms.repository;

import com.bms.entity.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RefundRepository extends JpaRepository<Refund, Long> {
    List<Refund> findByRefundDateBetween(LocalDateTime startDate, LocalDateTime endDate);
}
