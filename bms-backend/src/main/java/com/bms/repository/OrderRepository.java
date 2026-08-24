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
    ORDER BY o.createdAt DESC
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
}