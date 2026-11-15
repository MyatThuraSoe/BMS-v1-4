package com.bms.repository;

import com.bms.entity.StockTransfer;
import com.bms.entity.StockTransfer.TransferStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;

@Repository
public interface StockTransferRepository extends JpaRepository<StockTransfer, Long> {

    @EntityGraph(attributePaths = {"fromLocation", "toLocation", "items", "items.product"})
    Page<StockTransfer> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    @EntityGraph(attributePaths = {"fromLocation", "toLocation", "items", "items.product"})
    Optional<StockTransfer> findById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"fromLocation", "toLocation", "items", "items.product"})
    @Query("SELECT t FROM StockTransfer t WHERE t.id = :id")
    Optional<StockTransfer> findByIdForUpdate(@Param("id") Long id);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE StockTransfer t SET t.status = :next WHERE t.id = :id AND t.status = :expected")
    int transitionStatus(@Param("id") Long id,
                         @Param("expected") TransferStatus expected,
                         @Param("next") TransferStatus next);

    long countByTransferNumberStartingWith(String prefix);
}
