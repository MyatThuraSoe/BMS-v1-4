package com.bms.repository;

import com.bms.entity.Promotion;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    @EntityGraph(attributePaths = {"product", "category", "tiers"})
    @Query("""
        SELECT DISTINCT p FROM Promotion p
        WHERE p.isActive = true
          AND (p.startDate IS NULL OR p.startDate <= :now)
          AND (p.endDate IS NULL OR p.endDate >= :now)
        ORDER BY p.priority DESC
        """)
    List<Promotion> findActiveAt(@Param("now") LocalDateTime now);

    @EntityGraph(attributePaths = {"product", "category", "tiers"})
    List<Promotion> findAllByOrderByPriorityDesc();
}
