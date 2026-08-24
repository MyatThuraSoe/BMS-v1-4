package com.bms.repository;

import com.bms.entity.SaleReturn;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface SaleReturnRepository extends JpaRepository<SaleReturn, Long> {
    List<SaleReturn> findByReturnDateBetween(LocalDateTime startDate, LocalDateTime endDate);

    List<SaleReturn> findBySaleIdOrderByReturnDateDesc(Long saleId);

    List<SaleReturn> findBySaleIdInOrderByReturnDateDesc(Collection<Long> saleIds);

    @Query("""
        SELECT r FROM SaleReturn r
        WHERE (:saleId IS NULL OR r.sale.id = :saleId)
          AND (:invoice IS NULL OR :invoice = ''
               OR LOWER(r.sale.invoiceNumber) LIKE LOWER(CONCAT('%', :invoice, '%')))
        """)
    Page<SaleReturn> findFilteredReturns(@Param("saleId") Long saleId,
                                         @Param("invoice") String invoice,
                                         Pageable pageable);
}
