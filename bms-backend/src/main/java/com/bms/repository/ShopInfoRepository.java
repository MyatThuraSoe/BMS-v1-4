package com.bms.repository;

import com.bms.entity.ShopInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ShopInfoRepository extends JpaRepository<ShopInfo, Long> {
    
    @Query("SELECT s FROM ShopInfo s ORDER BY s.id")
    Optional<ShopInfo> findFirst();
}
