package com.bms.repository;

import com.bms.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySkuIgnoreCase(String sku);
    boolean existsBySkuIgnoreCase(String sku);

    Page<Product> findByNameContainingIgnoreCase(String name, Pageable pageable);

    // OPTIMIZED: Fetches category in a single JOIN query to prevent N+1 problem
    @EntityGraph(attributePaths = {"category"})
    @Query("SELECT p FROM Product p WHERE p.isActive = true AND p.deletedAt IS NULL ORDER BY p.name")
    Page<Product> findActiveProducts(Pageable pageable);

    // OPTIMIZED: Fetches category in a single JOIN query
    @EntityGraph(attributePaths = {"category"})
    @Query("SELECT p FROM Product p WHERE p.isActive = true AND p.deletedAt IS NULL AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<Product> searchActiveProducts(@Param("keyword") String keyword, Pageable pageable);

    // OPTIMIZED: Database-level filtering for low stock (fixes broken pagination)
    @Query("SELECT p FROM Product p WHERE p.isActive = true AND p.deletedAt IS NULL AND p.stockQuantity <= p.minStockLevel")
    Page<Product> findLowStockProducts(Pageable pageable);

    // OPTIMIZED: Database-level filtering for low stock BY CATEGORY (fixes broken pagination)
    @Query("SELECT p FROM Product p WHERE p.category.id = :categoryId AND p.isActive = true AND p.deletedAt IS NULL AND p.stockQuantity <= p.minStockLevel")
    Page<Product> findLowStockProductsByCategoryId(@Param("categoryId") Long categoryId, Pageable pageable);

    // OPTIMIZED: Fetches category in a single JOIN query for category filtering
    @EntityGraph(attributePaths = {"category"})
    @Query("SELECT p FROM Product p WHERE p.category.id = :categoryId AND p.isActive = true AND p.deletedAt IS NULL ORDER BY p.name")
    Page<Product> findActiveProductsByCategoryId(@Param("categoryId") Long categoryId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    @Query("""
        SELECT p
        FROM Product p
        LEFT JOIN SaleItem si ON si.product.id = p.id
        LEFT JOIN Sale s ON s.id = si.sale.id
        WHERE p.isActive = true
        AND p.deletedAt IS NULL
        AND (s IS NULL OR (s.isVoided = false AND s.isActive = true AND s.deletedAt IS NULL))
        GROUP BY p.id
        ORDER BY COALESCE(SUM(si.quantity), 0) DESC
    """)
    Page<Product> findMostSoldProducts(Pageable pageable);

    @Query("""
        SELECT p
        FROM Product p
        LEFT JOIN SaleItem si ON si.product.id = p.id
        LEFT JOIN Sale s ON s.id = si.sale.id
        WHERE p.isActive = true
        AND p.deletedAt IS NULL
        AND (s IS NULL OR (s.isVoided = false AND s.isActive = true AND s.deletedAt IS NULL))
        GROUP BY p.id
        ORDER BY COALESCE(SUM(si.quantity), 0) ASC
    """)
    Page<Product> findLeastSoldProducts(Pageable pageable);

    List<Product> findByNameContainingIgnoreCaseOrSkuContainingIgnoreCase(String name, String sku, Pageable pageable);

    @Query("""
        SELECT p.id, p.name, c.name, p.stockQuantity, p.costPrice, MAX(s.saleDate)
        FROM Product p
        LEFT JOIN p.category c
        LEFT JOIN SaleItem si ON si.product.id = p.id AND si.sale.isVoided = false
        LEFT JOIN si.sale s
        WHERE p.isActive = true AND p.stockQuantity > 0 AND p.deletedAt IS NULL
        GROUP BY p.id, p.name, c.name, p.stockQuantity, p.costPrice
        HAVING MAX(s.saleDate) IS NULL OR MAX(s.saleDate) < :cutoffDate
        ORDER BY MAX(s.saleDate) ASC NULLS FIRST
        """)
    List<Object[]> findDeadStock(@Param("cutoffDate") java.time.LocalDateTime cutoffDate);

    // -----------------------------------------------------------------------
    // Inventory dashboard aggregates (active, non-deleted products only)
    // -----------------------------------------------------------------------

    @Query("""
        SELECT COUNT(p),
               COALESCE(SUM(CASE WHEN p.stockQuantity > p.minStockLevel THEN 1 ELSE 0 END), 0),
               COALESCE(SUM(CASE WHEN p.stockQuantity > 0 AND p.stockQuantity <= p.minStockLevel THEN 1 ELSE 0 END), 0),
               COALESCE(SUM(CASE WHEN p.stockQuantity <= 0 THEN 1 ELSE 0 END), 0)
        FROM Product p
        WHERE p.isActive = true AND p.deletedAt IS NULL
        """)
    List<Object[]> getStockStatusCounts();

    @Query("""
        SELECT COALESCE(SUM(p.stockQuantity), 0),
               COALESCE(SUM(COALESCE(p.costPrice, 0) * p.stockQuantity), 0),
               COALESCE(SUM(COALESCE(p.unitPrice, 0) * p.stockQuantity), 0)
        FROM Product p
        WHERE p.isActive = true AND p.deletedAt IS NULL
        """)
    List<Object[]> getInventoryValues();

    @Query("""
        SELECT c.id, c.name,
               COUNT(p),
               COALESCE(SUM(p.stockQuantity), 0),
               COALESCE(SUM(COALESCE(p.costPrice, 0) * p.stockQuantity), 0),
               COALESCE(SUM(COALESCE(p.unitPrice, 0) * p.stockQuantity), 0)
        FROM Product p
        JOIN p.category c
        WHERE p.isActive = true AND p.deletedAt IS NULL
        GROUP BY c.id, c.name
        ORDER BY COALESCE(SUM(COALESCE(p.unitPrice, 0) * p.stockQuantity), 0) DESC
        """)
    List<Object[]> getCategoryInventoryBreakdown();

    @Query("""
        SELECT p.id, p.name, p.sku, c.name, p.stockQuantity, p.minStockLevel
        FROM Product p
        LEFT JOIN p.category c
        WHERE p.isActive = true AND p.deletedAt IS NULL
          AND p.stockQuantity <= p.minStockLevel
        ORDER BY (p.minStockLevel - p.stockQuantity) DESC, p.name ASC
        """)
    List<Object[]> findLowStockRows(Pageable pageable);
}