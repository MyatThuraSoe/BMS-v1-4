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

    // Uniqueness checks ignore soft-deleted rows so deleted suppliers' values reuse works.
    @Query("SELECT COUNT(s) > 0 FROM Supplier s WHERE s.deletedAt IS NULL AND s.email = :email")
    boolean existsByEmail(@Param("email") String email);

    @Query("SELECT COUNT(s) > 0 FROM Supplier s WHERE s.deletedAt IS NULL AND (s.phone = :phone OR EXISTS (SELECT 1 FROM s.phones sp WHERE sp.phone = :phone))")
    boolean existsByPhone(@Param("phone") String phone);
    
    Optional<Supplier> findByName(String name);
    
    @Query("SELECT s FROM Supplier s WHERE s.isActive = true AND s.deletedAt IS NULL")
    Page<Supplier> findActiveSuppliers(Pageable pageable);
    
    @Query("SELECT s FROM Supplier s WHERE s.isActive = true AND s.deletedAt IS NULL AND " +
           "(LOWER(s.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(s.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(s.phone) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "EXISTS (SELECT 1 FROM s.phones sp WHERE LOWER(sp.phone) LIKE LOWER(CONCAT('%', :keyword, '%'))))")
    Page<Supplier> searchActiveSuppliers(@Param("keyword") String keyword, Pageable pageable);
}
