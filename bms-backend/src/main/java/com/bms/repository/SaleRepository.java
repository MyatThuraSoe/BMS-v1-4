package com.bms.repository;

import com.bms.entity.Sale;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

@Repository
public interface SaleRepository extends JpaRepository<Sale, Long> {
    Optional<Sale> findByInvoiceNumber(String invoiceNumber);
    boolean existsByInvoiceNumber(String invoiceNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Sale s WHERE s.id = :id")
    Optional<Sale> findByIdForUpdate(@Param("id") Long id);
    
    @EntityGraph(attributePaths = {"items", "items.product", "customer"})
    @Query("SELECT s FROM Sale s WHERE s.isActive = true AND s.deletedAt IS NULL ORDER BY s.saleDate DESC")
    Page<Sale> findActiveSales(Pageable pageable);
    
    @EntityGraph(attributePaths = {"items", "items.product", "customer"})
    @Query("SELECT s FROM Sale s WHERE s.isActive = true AND s.deletedAt IS NULL AND " +
           "LOWER(s.invoiceNumber) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    Page<Sale> searchActiveSales(@Param("keyword") String keyword, Pageable pageable);
    
    @EntityGraph(attributePaths = {"items", "items.product", "customer"})
    @Query("SELECT s FROM Sale s WHERE s.isActive = true AND s.deletedAt IS NULL AND " +
           "s.cashierId = :cashierId ORDER BY s.saleDate DESC")
    Page<Sale> findByCashierId(@Param("cashierId") Long cashierId, Pageable pageable);
    
    @EntityGraph(attributePaths = {"items", "items.product", "customer"})
    @Query("SELECT s FROM Sale s WHERE s.isActive = true AND s.deletedAt IS NULL AND " +
           "s.customer.id = :customerId ORDER BY s.saleDate DESC")
    Page<Sale> findByCustomerId(@Param("customerId") Long customerId, Pageable pageable);
    
    @EntityGraph(attributePaths = {"items", "items.product", "customer"})
    @Query("SELECT s FROM Sale s WHERE s.isActive = true AND s.deletedAt IS NULL AND " +
           "s.saleDate BETWEEN :startDate AND :endDate ORDER BY s.saleDate DESC")
    Page<Sale> findByDateRange(@Param("startDate") LocalDateTime startDate, 
                               @Param("endDate") LocalDateTime endDate, 
                               Pageable pageable);
    
    @EntityGraph(attributePaths = {"items", "items.product", "customer"})
    @Query("SELECT s FROM Sale s WHERE s.isActive = true AND s.deletedAt IS NULL AND " +
           "s.isVoided = false ORDER BY s.saleDate DESC")
    Page<Sale> findNonVoidedSales(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
    SELECT s
    FROM Sale s
    WHERE s.invoiceNumber LIKE CONCAT(:prefix, '%')
    ORDER BY s.invoiceNumber DESC
    """)
    List<Sale> findLastInvoicesByPrefix(@Param("prefix") String prefix, org.springframework.data.domain.Pageable pageable);

    @Query("""
    SELECT s.invoiceNumber
    FROM Sale s
    WHERE s.invoiceNumber LIKE CONCAT(:prefix, '%')
    ORDER BY s.invoiceNumber DESC
    """)
    List<String> findInvoiceNumbersByPrefix(@Param("prefix") String prefix, org.springframework.data.domain.Pageable pageable);

    @EntityGraph(attributePaths = {"items", "items.product", "customer"})
    @Query("""
    SELECT s FROM Sale s
    WHERE s.isActive = true AND s.deletedAt IS NULL AND s.isVoided = false
    AND (:startDate IS NULL OR s.saleDate >= :startDate)
    AND (:endDate IS NULL OR s.saleDate < :endDate)
    AND (:customerId IS NULL OR s.customer.id = :customerId)
    AND (:invoice IS NULL OR LOWER(s.invoiceNumber) LIKE LOWER(CONCAT('%', :invoice, '%')))
    ORDER BY s.saleDate DESC
    """)
    Page<Sale> findFilteredSales(
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate,
        @Param("customerId") Long customerId,
        @Param("invoice") String invoice,
        Pageable pageable
    );

    // Customer Statics
    @Query("SELECT SUM(s.totalAmount) FROM Sale s WHERE s.customer.id = :customerId AND s.isActive = true AND s.deletedAt IS NULL AND s.isVoided = false AND s.saleDate >= :startDate")
    BigDecimal sumTotalAmountByCustomerIdAndDateAfter(@Param("customerId") Long customerId, @Param("startDate") java.time.LocalDateTime startDate);

    @Query("SELECT COUNT(s) FROM Sale s WHERE s.customer.id = :customerId AND s.isActive = true AND s.deletedAt IS NULL AND s.isVoided = false")
    Long countInvoicesByCustomerId(@Param("customerId") Long customerId);

    @Query("SELECT si.product.id, si.product.name, SUM(si.quantity) as totalQty, SUM(si.totalPrice) as totalAmt " +
            "FROM SaleItem si JOIN si.sale s " +
            "WHERE s.customer.id = :customerId AND s.isActive = true AND s.deletedAt IS NULL AND s.isVoided = false " +
            "GROUP BY si.product.id, si.product.name " +
            "ORDER BY totalQty DESC")
    List<Object[]> findTopProductsByCustomerId(@Param("customerId") Long customerId, org.springframework.data.domain.Pageable pageable);

    @Query("SELECT CAST(s.saleDate AS DATE), SUM(s.totalAmount) " +
            "FROM Sale s " +
            "WHERE s.customer.id = :customerId " +
            "AND YEAR(s.saleDate) = :year " +
            "AND s.isActive = true " +
            "AND s.deletedAt IS NULL " +
            "AND s.isVoided = false " +
            "GROUP BY CAST(s.saleDate AS DATE) " +
            "ORDER BY CAST(s.saleDate AS DATE)")
    List<Object[]> findDailySpendingByCustomerIdAndYear(@Param("customerId") Long customerId, @Param("year") int year);

    List<Sale> findByInvoiceNumberContainingIgnoreCase(String invoiceNumber, Pageable pageable);

    @Query("SELECT s FROM Sale s WHERE s.cashShiftId = :shiftId AND s.isActive = true AND s.deletedAt IS NULL ORDER BY s.saleDate")
    List<Sale> findByCashShiftId(@Param("shiftId") Long shiftId);

    @Query("""
        SELECT COALESCE(SUM(s.totalAmount), 0)
        FROM Sale s
        WHERE s.cashShiftId = :shiftId AND s.paymentMethod = 'CASH' AND s.isVoided = false
          AND s.isActive = true AND s.deletedAt IS NULL
        """)
    BigDecimal sumNetCashSalesByShiftId(@Param("shiftId") Long shiftId);

    @Query("""
        SELECT COALESCE(SUM(r.totalReturnAmount), 0)
        FROM SaleReturn r
        WHERE r.sale.cashShiftId = :shiftId AND r.returnDate >= :shiftStart
          AND r.sale.isActive = true AND r.sale.deletedAt IS NULL
          AND (r.sale.saleType <> com.bms.entity.Sale.SaleType.CREDIT
               OR r.sale.paymentStatus = com.bms.entity.Sale.PaymentStatus.PAID)
        """)
    BigDecimal sumReturnsDuringShift(@Param("shiftId") Long shiftId, @Param("shiftStart") LocalDateTime shiftStart);

    // Customer LTV — aggregate per customer across all non-voided sales
    @Query("""
        SELECT s.customer.id, s.customer.firstName, s.customer.lastName, s.customer.phone,
               COUNT(s.id), SUM(s.totalAmount), MIN(s.saleDate), MAX(s.saleDate)
        FROM Sale s
        WHERE s.customer IS NOT NULL
          AND s.isVoided = false
          AND s.isActive = true
          AND s.deletedAt IS NULL
        GROUP BY s.customer.id, s.customer.firstName, s.customer.lastName, s.customer.phone
        ORDER BY SUM(s.totalAmount) DESC
        """)
    List<Object[]> findCustomerLtvData();

    // Retention — distinct customer IDs with sales in a date range
    @Query("""
        SELECT DISTINCT s.customer.id
        FROM Sale s
        WHERE s.customer IS NOT NULL
          AND s.isVoided = false
          AND s.isActive = true
          AND s.deletedAt IS NULL
          AND s.saleDate >= :startDate AND s.saleDate < :endDate
        """)
    List<Long> findDistinctCustomerIdsInRange(
        @Param("startDate") java.time.LocalDateTime startDate,
        @Param("endDate") java.time.LocalDateTime endDate
    );

    interface CashierStats {
        long getTotalSales();
        BigDecimal getTotalRevenue();
    }

    @Query("""
        SELECT COUNT(s) AS totalSales, COALESCE(SUM(s.totalAmount), 0) AS totalRevenue
        FROM Sale s
        WHERE s.cashierId = :cashierId AND s.isVoided = false
          AND s.isActive = true AND s.deletedAt IS NULL
        """)
    CashierStats findCashierStats(@Param("cashierId") Long cashierId);

    @Query("""
        SELECT COUNT(s) AS totalSales, COALESCE(SUM(s.totalAmount), 0) AS totalRevenue
        FROM Sale s
        WHERE s.cashierId = :cashierId AND s.isVoided = false AND s.saleDate >= :since
          AND s.isActive = true AND s.deletedAt IS NULL
        """)
    CashierStats findCashierStatsSince(@Param("cashierId") Long cashierId, @Param("since") LocalDateTime since);

    // ---- Accounts Receivable ----

    // Outstanding credit invoices (paymentStatus != PAID), excluding voided /
    // soft-deleted rows, sorted by dueDate (earliest first) for aging.
    @EntityGraph(attributePaths = {"customer"})
    @Query("""
        SELECT s FROM Sale s
        WHERE s.saleType = com.bms.entity.Sale.SaleType.CREDIT
          AND s.paymentStatus <> com.bms.entity.Sale.PaymentStatus.PAID
          AND s.isVoided = false
          AND s.isActive = true
          AND s.deletedAt IS NULL
          AND (:keyword IS NULL OR :keyword = ''
               OR LOWER(s.invoiceNumber) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(s.customerDisplayName) LIKE LOWER(CONCAT('%', :keyword, '%')))
        ORDER BY
          CASE WHEN s.dueDate IS NULL THEN 1 ELSE 0 END,
          s.dueDate ASC,
          s.saleDate DESC
        """)
    Page<Sale> findOutstandingAr(@Param("keyword") String keyword, Pageable pageable);

    // Total money still owed across all outstanding credit invoices
    // (SUM(totalAmount) - SUM(amountPaid)), matching the findOutstandingAr filter.
    @Query("""
        SELECT COALESCE(SUM(s.totalAmount) - COALESCE(SUM(s.amountPaid), 0), 0)
        FROM Sale s
        WHERE s.saleType = com.bms.entity.Sale.SaleType.CREDIT
          AND s.paymentStatus <> com.bms.entity.Sale.PaymentStatus.PAID
          AND s.isVoided = false
          AND s.isActive = true
          AND s.deletedAt IS NULL
        """)
    BigDecimal sumOutstandingAr();

    // Full AR history (including already-paid invoices) for a single customer.
    @EntityGraph(attributePaths = {"customer"})
    @Query("""
        SELECT s FROM Sale s
        WHERE s.saleType = com.bms.entity.Sale.SaleType.CREDIT
          AND s.customer.id = :customerId
          AND s.isActive = true
          AND s.deletedAt IS NULL
        ORDER BY s.saleDate DESC
        """)
    Page<Sale> findArHistoryByCustomerId(@Param("customerId") Long customerId, Pageable pageable);
}
