package com.bms.repository;

import com.bms.entity.CountSheet;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CountSheetRepository extends JpaRepository<CountSheet, Long> {

    Optional<CountSheet> findBySheetNumber(String sheetNumber);
    Optional<CountSheet> findBySyncKey(String syncKey);
    Page<CountSheet> findByStatusInOrderByCreatedAtDesc(Collection<CountSheet.CountStatus> statuses, Pageable pageable);
    Page<CountSheet> findAllByOrderByCreatedAtDesc(Pageable pageable);
    List<CountSheet> findByStatusIn(Collection<CountSheet.CountStatus> statuses);
    long countBySheetNumberStartingWith(String prefix);

    @Query("""
        SELECT DISTINCT i.product.id FROM CountSheetItem i
        WHERE i.countSheet.status IN :statuses
        """)
    List<Long> findProductIdsOnOpenSheets(@Param("statuses") Collection<CountSheet.CountStatus> statuses);
}
