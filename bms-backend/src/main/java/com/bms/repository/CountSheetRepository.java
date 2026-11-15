package com.bms.repository;

import com.bms.entity.CountSheet;
import com.bms.entity.CountSheet.CountStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CountSheetRepository extends JpaRepository<CountSheet, Long> {

    Optional<CountSheet> findBySheetNumber(String sheetNumber);
    Optional<CountSheet> findBySyncKey(String syncKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM CountSheet s WHERE s.id = :id")
    Optional<CountSheet> findByIdForUpdate(@Param("id") Long id);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE CountSheet s SET s.status = :next WHERE s.id = :id AND s.status = :expected")
    int transitionStatus(@Param("id") Long id,
                         @Param("expected") CountStatus expected,
                         @Param("next") CountStatus next);

    Page<CountSheet> findByStatusInOrderByCreatedAtDescIdDesc(Collection<CountSheet.CountStatus> statuses, Pageable pageable);
    Page<CountSheet> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);
    List<CountSheet> findByStatusIn(Collection<CountSheet.CountStatus> statuses);
    long countBySheetNumberStartingWith(String prefix);

    @Query("""
        SELECT DISTINCT i.product.id FROM CountSheetItem i
        WHERE i.countSheet.status IN :statuses
        """)
    List<Long> findProductIdsOnOpenSheets(@Param("statuses") Collection<CountSheet.CountStatus> statuses);
}
