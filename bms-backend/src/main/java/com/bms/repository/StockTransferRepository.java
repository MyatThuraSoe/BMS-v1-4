package com.bms.repository;

import com.bms.entity.StockTransfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StockTransferRepository extends JpaRepository<StockTransfer, Long> {

    @EntityGraph(attributePaths = {"fromLocation", "toLocation", "items", "items.product"})
    Page<StockTransfer> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = {"fromLocation", "toLocation", "items", "items.product"})
    Optional<StockTransfer> findById(Long id);

    long countByTransferNumberStartingWith(String prefix);
}
