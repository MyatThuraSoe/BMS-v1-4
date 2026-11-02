package com.bms.repository;

import com.bms.entity.CountSheetItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CountSheetItemRepository extends JpaRepository<CountSheetItem, Long> {

    @Query("""
        SELECT i FROM CountSheetItem i
        JOIN FETCH i.product p
        LEFT JOIN FETCH p.category
        WHERE i.countSheet.id = :sheetId
        ORDER BY p.name
        """)
    List<CountSheetItem> findWithProductBySheetId(@Param("sheetId") Long sheetId);

    Optional<CountSheetItem> findByIdAndCountSheetId(Long id, Long countSheetId);

    long countByCountSheetId(Long countSheetId);
}
