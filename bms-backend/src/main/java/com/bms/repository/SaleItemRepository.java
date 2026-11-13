package com.bms.repository;

import com.bms.entity.SaleItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SaleItemRepository extends JpaRepository<SaleItem, Long> {

    boolean existsByProductId(Long productId);

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

    /**
     * One row per sale line for a product, including the sale-level discount
     * context. Revenue cannot be summed as unitPrice * quantity because the POS
     * stores discounts on the sale header; the service allocates each sale's
     * discount proportionally to the line value.
     */
    @Query("""
        SELECT si.sale.subtotal,
               si.sale.discountAmount,
               COALESCE(si.totalPrice, si.unitPrice * si.quantity),
               si.quantity,
               COALESCE(si.quantityRefunded, 0),
               si.costPriceAtSale
        FROM SaleItem si
        WHERE si.product.id = :productId
          AND si.sale.isVoided = false
          AND si.sale.isActive = true
          AND si.sale.deletedAt IS NULL
        """)
    List<Object[]> getSalesSummaryForProduct(@Param("productId") Long productId);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT si FROM SaleItem si WHERE si.id = :id")
    Optional<SaleItem> findByIdForUpdate(@Param("id") Long id);

    @Query("""
        SELECT si.product.id,
               COALESCE(SUM(COALESCE(si.totalPrice, si.unitPrice * si.quantity)), 0)
        FROM SaleItem si
        WHERE si.sale.isVoided = false
          AND si.sale.isActive = true
          AND si.sale.deletedAt IS NULL
          AND si.sale.saleDate >= :fromDate
        GROUP BY si.product.id
        """)
    List<Object[]> findRevenueByProductSince(@Param("fromDate") java.time.LocalDateTime fromDate);

    @Query("""
        SELECT si2.product.id, si2.product.name, COUNT(DISTINCT si1.sale.id)
        FROM SaleItem si1
        JOIN SaleItem si2 ON si1.sale.id = si2.sale.id AND si1.product.id != si2.product.id
        WHERE si1.product.id = :productId AND si1.sale.isVoided = false
        GROUP BY si2.product.id, si2.product.name
        ORDER BY COUNT(DISTINCT si1.sale.id) DESC
        """)
    List<Object[]> findFrequentlyBoughtWith(@Param("productId") Long productId, Pageable pageable);

    /**
     * One row per sale containing the product (saleDate, net quantity, net
     * revenue). The caller buckets rows into a day-of-week x hour-of-day grid
     * in Java: extracting day-of-week/hour in SQL is not portable across
     * H2 / MySQL / SQLite, and one row per sale is a small transfer even for
     * long ranges. Voided, inactive and soft-deleted sales are excluded, and
     * refunds are netted out of quantity and revenue.
     *
     * @param fromDate nullable; null means "all time"
     */
    @Query("""
        SELECT si.sale.id,
               si.sale.saleDate,
               SUM(si.quantity),
               SUM(COALESCE(si.quantityRefunded, 0)),
               SUM(COALESCE(si.totalPrice, si.unitPrice * si.quantity)),
               si.sale.subtotal,
               si.sale.discountAmount
        FROM SaleItem si
        WHERE si.product.id = :productId
          AND si.sale.isVoided = false
          AND si.sale.isActive = true
          AND si.sale.deletedAt IS NULL
          AND (:fromDate IS NULL OR si.sale.saleDate >= :fromDate)
        GROUP BY si.sale.id, si.sale.saleDate, si.sale.subtotal, si.sale.discountAmount
        """)
    List<Object[]> findSalesTimelineForProduct(@Param("productId") Long productId,
                                               @Param("fromDate") java.time.LocalDateTime fromDate);
}
