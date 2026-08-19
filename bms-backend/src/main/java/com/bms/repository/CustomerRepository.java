package com.bms.repository;

import com.bms.entity.Customer;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    boolean existsByCustomerCode(String customerCode);
    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);

    Optional<Customer> findByCustomerCode(String customerCode);
    Optional<Customer> findByEmail(String email);
    Optional<Customer> findByPhone(String phone);

    // Pessimistic row lock — used when mutating currentBalance so two
    // concurrent credit sales/payments can never race on the same customer.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Customer c WHERE c.id = :id")
    Optional<Customer> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT c FROM Customer c WHERE c.isActive = true AND c.deletedAt IS NULL")
    Page<Customer> findActiveCustomers(Pageable pageable);

    @Query("SELECT c FROM Customer c WHERE c.isActive = true AND c.deletedAt IS NULL AND " +
            "(:city IS NULL OR :city = '' OR LOWER(c.city) LIKE LOWER(CONCAT('%', :city, '%'))) AND " +
            "(:keyword IS NULL OR :keyword = '' OR " +
            "LOWER(c.firstName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(c.lastName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(c.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(c.phone) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(c.customerCode) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(c.city) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<Customer> searchActiveCustomers(@Param("keyword") String keyword, @Param("city") String city, Pageable pageable);

    @Query("SELECT DISTINCT c.city FROM Customer c WHERE c.isActive = true AND c.deletedAt IS NULL " +
            "AND c.city IS NOT NULL AND c.city <> '' ORDER BY c.city")
    List<String> findDistinctActiveCities();

    // REPLACED the broken method with this safe @Query version
    @Query("SELECT c FROM Customer c WHERE c.isActive = true AND c.deletedAt IS NULL AND " +
            "(LOWER(c.firstName) LIKE LOWER(CONCAT('%', :name, '%')) OR " +
            "LOWER(c.lastName) LIKE LOWER(CONCAT('%', :name, '%')) OR " +
            "LOWER(c.phone) LIKE LOWER(CONCAT('%', :phone, '%')))")
    List<Customer> findByNameOrPhone(@Param("name") String name, @Param("phone") String phone, Pageable pageable);
}