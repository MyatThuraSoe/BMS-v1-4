package com.bms.repository;

import com.bms.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {
    boolean existsByCustomerCode(String customerCode);
    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);
    
    Optional<Customer> findByCustomerCode(String customerCode);
    Optional<Customer> findByEmail(String email);
    Optional<Customer> findByPhone(String phone);
    
    @Query("SELECT c FROM Customer c WHERE c.isActive = true AND c.deletedAt IS NULL")
    Page<Customer> findActiveCustomers(Pageable pageable);
    
    @Query("SELECT c FROM Customer c WHERE c.isActive = true AND c.deletedAt IS NULL AND " +
           "(LOWER(c.firstName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(c.lastName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(c.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(c.phone) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(c.customerCode) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<Customer> searchActiveCustomers(@Param("keyword") String keyword, Pageable pageable);
}
