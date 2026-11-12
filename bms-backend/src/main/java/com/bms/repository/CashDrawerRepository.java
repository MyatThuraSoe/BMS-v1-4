package com.bms.repository;

import com.bms.entity.CashDrawer;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CashDrawerRepository extends JpaRepository<CashDrawer, Long> {
    Optional<CashDrawer> findByCode(String code);

    Optional<CashDrawer> findFirstByIsActiveTrueAndDeletedAtIsNullOrderByIdAsc();

    List<CashDrawer> findByIsActiveTrueAndDeletedAtIsNullOrderByIdAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM CashDrawer d WHERE d.id = :id")
    Optional<CashDrawer> findByIdForUpdate(@Param("id") Long id);
}
