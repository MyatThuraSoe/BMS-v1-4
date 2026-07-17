package com.bms.repository;

import com.bms.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findBySku(String sku);
    Optional<Product> findByBarcode(String barcode);
    boolean existsBySku(String sku);
    boolean existsByBarcode(String barcode);
    
    Page<Product> findByNameContainingIgnoreCase(String name, Pageable pageable);
    
    @Query("SELECT p FROM Product p WHERE p.isActive = true AND p.deletedAt IS NULL ORDER BY p.name")
    Page<Product> findAllActive(Pageable pageable);
    
    @Query("SELECT p FROM Product p WHERE p.isActive = true AND p.deletedAt IS NULL AND (p.name LIKE %:keyword% OR p.sku LIKE %:keyword% OR p.barcode LIKE %:keyword%)")
    Page<Product> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
    
    @Query("SELECT p FROM Product p WHERE p.isActive = true AND p.deletedAt IS NULL AND p.stockQuantity <= p.minStockLevel")
    Page<Product> findLowStockProducts(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);
    
    @Query("SELECT p FROM Product p WHERE p.category.id = :categoryId AND p.isActive = true AND p.deletedAt IS NULL")
    Page<Product> findByCategoryId(@Param("categoryId") Long categoryId, Pageable pageable);

    Page<Product> findByCategoryIdAndIsActiveTrue(Long categoryId, Pageable pageable);
    
    // Alias methods for service compatibility
    @Query("SELECT p FROM Product p WHERE p.isActive = true AND p.deletedAt IS NULL ORDER BY p.name")
    Page<Product> findActiveProducts(Pageable pageable);
    
    @Query("SELECT p FROM Product p WHERE p.isActive = true AND p.deletedAt IS NULL AND (p.name LIKE %:keyword% OR p.sku LIKE %:keyword% OR p.barcode LIKE %:keyword%)")
    Page<Product> searchActiveProducts(@Param("keyword") String keyword, Pageable pageable);
}
