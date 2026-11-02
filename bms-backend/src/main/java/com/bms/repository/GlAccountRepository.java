package com.bms.repository;

import com.bms.entity.GlAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GlAccountRepository extends JpaRepository<GlAccount, Long> {
    Optional<GlAccount> findByCode(String code);
    List<GlAccount> findByIsActiveTrueOrderByCodeAsc();
    List<GlAccount> findAllByOrderByCodeAsc();
}
