package com.bms.repository;

import com.bms.entity.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByOrderNumber(String orderNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = {"items", "items.product", "customer"})
    @Query("""
    SELECT o FROM Order o
    WHERE o.isActive = true AND o.deletedAt IS NULL
    AND (:status IS NULL OR o.status = :status)
    AND (:startDate IS NULL OR o.createdAt >= :startDate)
    AND (:endDate IS NULL OR o.createdAt < :endDate)
    AND (:customerId IS NULL OR o.customer.id = :customerId)
    AND (:orderNumber IS NULL OR LOWER(o.orderNumber) LIKE LOWER(CONCAT('%', :orderNumber, '%')))
    ORDER BY o.createdAt DESC, o.id DESC
    """)
    Page<Order> findFilteredOrders(
        @Param("status") Order.OrderStatus status,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate,
        @Param("customerId") Long customerId,
        @Param("orderNumber") String orderNumber,
        Pageable pageable
    );

    @EntityGraph(attributePaths = {"items", "items.product", "customer"})
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") Long id);

    @EntityGraph(attributePaths = {"items", "items.product"})
    @Query("SELECT DISTINCT o FROM Order o JOIN o.items i "
            + "WHERE o.status = :status AND o.isActive = true "
            + "AND o.deletedAt IS NULL AND i.product.id = :productId")
    java.util.List<Order> findPendingOrdersForProduct(
            @Param("productId") Long productId,
            @Param("status") Order.OrderStatus status);

    /** O8: pending orders that have been holding stock since before {@code cutoff}. */
    @EntityGraph(attributePaths = {"items", "items.product"})
    @Query("""
    SELECT o FROM Order o
    WHERE o.status = com.bms.entity.Order.OrderStatus.PENDING
      AND o.isActive = true AND o.deletedAt IS NULL
      AND o.createdAt < :cutoff
    ORDER BY o.createdAt, o.id
    """)
    java.util.List<Order> findPendingOrdersOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /**
     * T3: pending orders still holding stock for one customer. A pending order
     * reserves units, so deleting the customer behind it leaves reservations no
     * screen can reach and stock that never frees.
     */
    @Query("""
    SELECT COUNT(o) FROM Order o
    WHERE o.status = com.bms.entity.Order.OrderStatus.PENDING
      AND o.isActive = true AND o.deletedAt IS NULL
      AND o.customer.id = :customerId
    """)
    long countPendingOrdersByCustomerId(@Param("customerId") Long customerId);
}