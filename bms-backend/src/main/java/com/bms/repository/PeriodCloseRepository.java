package com.bms.repository;

import com.bms.entity.PeriodClose;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodCloseRepository extends JpaRepository<PeriodClose, String> {
    java.util.List<PeriodClose> findByPeriodGreaterThanEqualAndPeriodLessThanEqual(String from, String to);
}