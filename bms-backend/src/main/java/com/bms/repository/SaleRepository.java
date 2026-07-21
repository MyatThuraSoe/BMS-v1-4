package com.bms.repository;

import com.bms.entity.Sale;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

@Repository
public interface SaleRepository extends JpaRepository<Sale, Long> {
    Optional<Sale> findByInvoiceNumber(String invoiceNumber);
    boolean existsByInvoiceNumber(String invoiceNumber);
    
    @Query("SELECT s FROM Sale s WHERE s.isActive = true AND s.deletedAt IS NULL ORDER BY s.saleDate DESC")
    Page<Sale> findActiveSales(Pageable pageable);
    
    @Query("SELECT s FROM Sale s WHERE s.isActive = true AND s.deletedAt IS NULL AND " +
           "LOWER(s.invoiceNumber) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    Page<Sale> searchActiveSales(@Param("keyword") String keyword, Pageable pageable);
    
    @Query("SELECT s FROM Sale s WHERE s.isActive = true AND s.deletedAt IS NULL AND " +
           "s.cashierId = :cashierId ORDER BY s.saleDate DESC")
    Page<Sale> findByCashierId(@Param("cashierId") Long cashierId, Pageable pageable);
    
    @Query("SELECT s FROM Sale s WHERE s.isActive = true AND s.deletedAt IS NULL AND " +
           "s.customer.id = :customerId ORDER BY s.saleDate DESC")
    Page<Sale> findByCustomerId(@Param("customerId") Long customerId, Pageable pageable);
    
    @Query("SELECT s FROM Sale s WHERE s.isActive = true AND s.deletedAt IS NULL AND " +
           "s.saleDate BETWEEN :startDate AND :endDate ORDER BY s.saleDate DESC")
    Page<Sale> findByDateRange(@Param("startDate") LocalDateTime startDate, 
                               @Param("endDate") LocalDateTime endDate, 
                               Pageable pageable);
    
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

    @Query("SELECT COALESCE(SUM(s.totalAmount), 0) FROM Sale s " +
           "WHERE s.cashShift.id = :shiftId AND s.paymentMethod = 'CASH' AND s.isVoided = false")
    java.math.BigDecimal sumCashSalesByShiftId(@Param("shiftId") Long shiftId);

    @Query("SELECT si.product.id, AVG(si.quantity * 1.0 / 30.0) " +
           "FROM SaleItem si " +
           "JOIN si.sale s " +
           "WHERE s.saleDate >= :startDate AND s.isVoided = false " +
           "GROUP BY si.product.id")
    java.util.List<Object[]> findAverageDailySalesByProductId(@Param("startDate") LocalDateTime startDate);
}
