package com.bms.repository;

import com.bms.entity.PurchaseItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PurchaseItemRepository extends JpaRepository<PurchaseItem, Long> {

    @Query("SELECT pi.purchase.supplier.id as supplierId, " +
           "pi.purchase.supplier.name as supplierName, " +
           "COUNT(pi) as timesPurchased, " +
           "MAX(pi.unitCost) as mostRecentUnitCost, " +
           "MAX(pi.purchase.purchaseDate) as mostRecentPurchaseDate, " +
           "SUM(pi.quantity) as totalQuantityPurchased, " +
           "SUM(pi.quantity * pi.unitCost) as totalAmountSpent " +
           "FROM PurchaseItem pi " +
           "WHERE pi.product.id = :productId " +
           "AND pi.purchase.isActive = true " +
           "AND pi.purchase.deletedAt IS NULL " +
           "GROUP BY pi.purchase.supplier.id, pi.purchase.supplier.name")
    List<Object[]> findProductSupplierHistory(@Param("productId") Long productId);

    @Query("SELECT pi.purchase.purchaseDate as purchaseDate, " +
           "pi.purchase.supplier.name as supplierName, " +
           "pi.purchase.supplier.id as supplierId, " +
           "pi.quantity as quantity, " +
           "pi.unitCost as unitCost " +
           "FROM PurchaseItem pi " +
           "WHERE pi.product.id = :productId " +
           "AND pi.purchase.isActive = true " +
           "AND pi.purchase.deletedAt IS NULL " +
           "ORDER BY pi.purchase.purchaseDate DESC")
    List<Object[]> findProductCostHistory(@Param("productId") Long productId);
}
