package com.bms.repository;

import com.bms.entity.PurchaseItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PurchaseItemRepository extends JpaRepository<PurchaseItem, Long> {
    
    @Query("SELECT pi FROM PurchaseItem pi JOIN pi.purchase p " +
           "WHERE pi.product.id = :productId " +
           "ORDER BY p.purchaseDate DESC")
    java.util.List<PurchaseItem> findTopByProductIdOrderByPurchaseDateDesc(@Param("productId") Long productId, org.springframework.data.domain.Pageable pageable);
    
    default PurchaseItem findTopByProductIdOrderByPurchaseDateDesc(Long productId) {
        var results = findTopByProductIdOrderByPurchaseDateDesc(productId, org.springframework.data.domain.PageRequest.of(0, 1));
        return results.isEmpty() ? null : results.get(0);
    }
}
