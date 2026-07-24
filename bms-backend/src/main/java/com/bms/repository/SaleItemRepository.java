package com.bms.repository;

import com.bms.entity.SaleItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SaleItemRepository extends JpaRepository<SaleItem, Long> {

    @Query("""
        SELECT si.sale.customer.id, si.sale.customer.firstName, si.sale.customer.lastName,
               si.sale.customer.phone,
               SUM(si.quantity - si.quantityRefunded),
               SUM(si.unitPrice * (si.quantity - si.quantityRefunded)),
               MAX(si.sale.saleDate)
        FROM SaleItem si
        WHERE si.product.id = :productId
          AND si.sale.customer IS NOT NULL
          AND si.sale.isVoided = false
        GROUP BY si.sale.customer.id, si.sale.customer.firstName,
                 si.sale.customer.lastName, si.sale.customer.phone
        ORDER BY SUM(si.quantity - si.quantityRefunded) DESC
        """)
    List<Object[]> findTopCustomersForProduct(@Param("productId") Long productId, Pageable pageable);

    @Query("""
        SELECT SUM(si.quantity - si.quantityRefunded),
               SUM(si.unitPrice * (si.quantity - si.quantityRefunded)),
               SUM((si.unitPrice - COALESCE(si.costPriceAtSale, 0)) * (si.quantity - si.quantityRefunded))
        FROM SaleItem si
        WHERE si.product.id = :productId
          AND si.sale.isVoided = false
        """)
    Object[] getSalesSummaryForProduct(@Param("productId") Long productId);
}
