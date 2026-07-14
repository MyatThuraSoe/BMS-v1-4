package com.bms.repository;

import com.bms.entity.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {
    List<ProductImage> findByProductId(Long productId);
    
    @Query("SELECT pi FROM ProductImage pi WHERE pi.product.id = :productId AND pi.isPrimary = true")
    ProductImage findPrimaryByProductId(@Param("productId") Long productId);
    
    @Modifying
    @Query("UPDATE ProductImage pi SET pi.isPrimary = false WHERE pi.product.id = :productId")
    void clearPrimaryImages(@Param("productId") Long productId);
    
    void deleteByProductId(Long productId);
    
    // Additional methods for service compatibility
    @Modifying
    @Query("UPDATE ProductImage pi SET pi.isPrimary = false WHERE pi.product.id = :productId")
    void unsetPrimaryImages(@Param("productId") Long productId);
    
    @Query("SELECT MAX(pi.displayOrder) FROM ProductImage pi WHERE pi.product.id = :productId")
    Integer findMaxDisplayOrder(@Param("productId") Long productId);
    
    @Query("SELECT pi FROM ProductImage pi WHERE pi.product.id = :productId ORDER BY pi.displayOrder ASC")
    List<ProductImage> findByProductIdOrderByDisplayOrderAsc(@Param("productId") Long productId);
    
    @Query("SELECT COUNT(pi) > 0 FROM ProductImage pi WHERE pi.product.id = :productId")
    boolean existsByProductId(@Param("productId") Long productId);
}
