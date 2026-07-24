package com.bms.repository;

import com.bms.entity.Supplier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SupplierRepository extends JpaRepository<Supplier, Long> {
    boolean existsByName(String name);
    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);
    
    Optional<Supplier> findByName(String name);
    
    @Query("SELECT s FROM Supplier s WHERE s.isActive = true AND s.deletedAt IS NULL")
    Page<Supplier> findActiveSuppliers(Pageable pageable);
    
    @Query("SELECT s FROM Supplier s WHERE s.isActive = true AND s.deletedAt IS NULL AND " +
           "(LOWER(s.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(s.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(s.phone) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<Supplier> searchActiveSuppliers(@Param("keyword") String keyword, Pageable pageable);
}
