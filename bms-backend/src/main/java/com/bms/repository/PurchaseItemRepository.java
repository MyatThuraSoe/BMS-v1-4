package com.bms.repository;

import com.bms.entity.PurchaseItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PurchaseItemRepository extends JpaRepository<PurchaseItem, Long> {

    boolean existsByProductId(Long productId);

    /**
     * One-off legacy backfill (C3): rows created before the receiving feature
     * were fully received at creation time but carry receivedQuantity = 0.
     * NULL receiving_status marks the same pre-feature era. Idempotent.
     */
    @Modifying
    @Query("UPDATE PurchaseItem pi SET pi.receivedQuantity = pi.quantity "
            + "WHERE pi.receivedQuantity = 0 AND pi.purchase.id IN "
            + "(SELECT p.id FROM Purchase p WHERE p.receivingStatus = com.bms.entity.Purchase.ReceivingStatus.RECEIVED "
            + "OR p.receivingStatus IS NULL)")
    int backfillLegacyReceivedQuantity();

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

    /**
     * P8 req 7: cost history covers only goods actually received (receivedQuantity > 0),
     * never-received POs are excluded. Carries the purchase unit cost, landed cost and
     * base-currency cost so the UI can label which cost it shows. Ordered by invoice
     * date then item id for a stable tiebreaker (P8 req 9 / O7).
     */
    @Query("SELECT pi.purchase.purchaseDate as purchaseDate, " +
           "supplier.name as supplierName, " +
           "supplier.id as supplierId, " +
           "pi.receivedQuantity as quantity, " +
           "pi.unitCost as unitCost, " +
           "pi.landedUnitCost as landedUnitCost, " +
           "pi.baseUnitCost as baseUnitCost, " +
           "pi.purchase.id as purchaseId " +
           "FROM PurchaseItem pi " +
           "LEFT JOIN pi.purchase.supplier supplier " +
           "WHERE pi.product.id = :productId " +
           "AND pi.purchase.isActive = true " +
           "AND pi.purchase.deletedAt IS NULL " +
           "AND pi.receivedQuantity > 0 " +
           "ORDER BY pi.purchase.purchaseDate DESC, pi.id DESC")
    List<Object[]> findProductCostHistory(@Param("productId") Long productId);

    /**
     * P8 req 6: received unit costs latest-first (invoice date DESC, item id DESC) so the
     * supplier history can show the most recent received cost rather than MAX(unitCost).
     */
    @Query("SELECT pi.purchase.supplier.id as supplierId, " +
           "pi.unitCost as unitCost, " +
           "pi.purchase.purchaseDate as purchaseDate " +
           "FROM PurchaseItem pi " +
           "WHERE pi.product.id = :productId " +
           "AND pi.purchase.isActive = true " +
           "AND pi.purchase.deletedAt IS NULL " +
           "AND pi.receivedQuantity > 0 " +
           "ORDER BY pi.purchase.purchaseDate DESC, pi.id DESC")
    List<Object[]> findReceivedUnitCostsLatestFirst(@Param("productId") Long productId);
}
