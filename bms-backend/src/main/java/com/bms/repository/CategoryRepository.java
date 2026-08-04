package com.bms.repository;

import com.bms.entity.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {
    Page<Category> findByNameContainingIgnoreCase(String name, Pageable pageable);
    
    @Query("SELECT c FROM Category c WHERE c.isActive = true AND c.deletedAt IS NULL")
    List<Category> findAllActive();
    
    @Query("SELECT c FROM Category c WHERE c.isActive = true AND c.deletedAt IS NULL ORDER BY c.name")
    Page<Category> findAllActive(Pageable pageable);
}
