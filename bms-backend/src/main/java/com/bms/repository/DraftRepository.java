package com.bms.repository;

import com.bms.entity.Draft;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DraftRepository extends JpaRepository<Draft, Long> {
    @EntityGraph(attributePaths = {"items", "items.product"})
    List<Draft> findByCashierIdAndIsActiveTrueOrderByCreatedAtDesc(Long cashierId);

    @EntityGraph(attributePaths = {"items", "items.product"})
    Optional<Draft> findByIdAndCashierIdAndIsActiveTrue(Long id, Long cashierId);
}