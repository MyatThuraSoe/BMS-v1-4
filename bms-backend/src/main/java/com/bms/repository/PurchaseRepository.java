package com.bms.repository;

import com.bms.entity.Purchase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

@Repository
public interface PurchaseRepository extends JpaRepository<Purchase, Long> {
    Optional<Purchase> findByPurchaseNumber(String purchaseNumber);
    boolean existsByPurchaseNumber(String purchaseNumber);
    
    @Query("SELECT p FROM Purchase p WHERE p.isActive = true AND p.deletedAt IS NULL ORDER BY p.purchaseDate DESC")
    Page<Purchase> findActivePurchases(Pageable pageable);
    
    @Query("SELECT p FROM Purchase p WHERE p.isActive = true AND p.deletedAt IS NULL AND " +
           "(LOWER(p.purchaseNumber) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(p.supplier.name) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<Purchase> searchActivePurchases(@Param("keyword") String keyword, Pageable pageable);
    
    @Query("SELECT p FROM Purchase p WHERE p.isActive = true AND p.deletedAt IS NULL AND " +
           "p.supplier.id = :supplierId ORDER BY p.purchaseDate DESC")
    Page<Purchase> findBySupplierId(@Param("supplierId") Long supplierId, Pageable pageable);
    
    @Query("SELECT p FROM Purchase p WHERE p.isActive = true AND p.deletedAt IS NULL AND " +
           "p.purchaseDate BETWEEN :startDate AND :endDate ORDER BY p.purchaseDate DESC")
    Page<Purchase> findByDateRange(@Param("startDate") LocalDate startDate, 
                                   @Param("endDate") LocalDate endDate, 
                                   Pageable pageable);

    // Claude told them to delete
//    // Additional method for purchase number generation (must be atomic)
//    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
//    @Query("SELECT p.purchaseNumber FROM Purchase p WHERE p.purchaseNumber LIKE :prefix% ORDER BY p.purchaseNumber DESC")
//    Optional<String> findAndLockLastPurchaseNumberByDatePrefix(@Param("prefix") String prefix);


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Purchase> findTopByPurchaseNumberStartingWithOrderByIdDesc(String prefix);
}
