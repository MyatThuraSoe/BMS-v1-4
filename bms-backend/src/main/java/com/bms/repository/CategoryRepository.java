package com.bms.repository;

import com.bms.entity.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {
    Page<Category> findByNameContainingIgnoreCase(String name, Pageable pageable);
    
    @Query("SELECT c FROM Category c WHERE c.isActive = true AND c.deletedAt IS NULL")
    List<Category> findAllActive();
    
    @Query("SELECT c FROM Category c WHERE c.isActive = true AND c.deletedAt IS NULL ORDER BY c.name")
    Page<Category> findAllActive(Pageable pageable);

    @Query("SELECT p.category.id, COUNT(p), " +
           "COALESCE(SUM(p.stockQuantity), 0), " +
           "COALESCE(SUM(p.stockQuantity * p.unitPrice), 0) " +
           "FROM Product p " +
           "WHERE p.isActive = true AND p.deletedAt IS NULL AND p.category IS NOT NULL " +
           "GROUP BY p.category.id")
    List<Object[]> findStockStatsByCategory();

    @Query("SELECT p.category.id, COUNT(p) " +
           "FROM Product p " +
           "WHERE p.isActive = true AND p.deletedAt IS NULL AND p.category IS NOT NULL " +
           "AND p.stockQuantity > 0 AND p.stockQuantity <= p.minStockLevel " +
           "GROUP BY p.category.id")
    List<Object[]> findLowStockCountsByCategory();

    @Query("SELECT p.category.id, COUNT(p) " +
           "FROM Product p " +
           "WHERE p.isActive = true AND p.deletedAt IS NULL AND p.category IS NOT NULL " +
           "AND p.stockQuantity <= 0 " +
           "GROUP BY p.category.id")
    List<Object[]> findOutOfStockCountsByCategory();

    @Query("SELECT p.category.id, SUM(si.quantity - COALESCE(si.quantityRefunded, 0)), COALESCE(SUM(si.totalPrice), 0) " +
           "FROM SaleItem si " +
           "JOIN si.sale s " +
           "JOIN si.product p " +
           "WHERE s.isVoided = false AND s.isActive = true AND p.category IS NOT NULL " +
           "GROUP BY p.category.id")
    List<Object[]> findSalesStatsByCategory();

    @Query("SELECT COUNT(p), COALESCE(SUM(p.stockQuantity), 0), COALESCE(SUM(p.stockQuantity * p.unitPrice), 0) " +
           "FROM Product p " +
           "WHERE p.isActive = true AND p.deletedAt IS NULL")
    List<Object[]> findOverallInventoryStats();

    @Query("SELECT COUNT(p) " +
           "FROM Product p " +
           "WHERE p.isActive = true AND p.deletedAt IS NULL " +
           "AND p.stockQuantity > 0 AND p.stockQuantity <= p.minStockLevel")
    Long countLowStockProducts();

    @Query("SELECT COUNT(p) " +
           "FROM Product p " +
           "WHERE p.isActive = true AND p.deletedAt IS NULL " +
           "AND p.stockQuantity <= 0")
    Long countOutOfStockProducts();

    @Query("SELECT COUNT(c) FROM Category c WHERE c.isActive = true AND c.deletedAt IS NULL")
    Long countActiveCategories();

    @Query("SELECT SUM(si.quantity - COALESCE(si.quantityRefunded, 0)), COALESCE(SUM(si.totalPrice), 0) " +
           "FROM SaleItem si " +
           "JOIN si.sale s " +
           "WHERE s.isVoided = false AND s.isActive = true")
    List<Object[]> findOverallSalesStats();
}
