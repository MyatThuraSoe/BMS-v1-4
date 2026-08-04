package com.bms.repository;

import com.bms.entity.ShopInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShopInfoRepository extends JpaRepository<ShopInfo, Long> {
    Optional<ShopInfo> findTopByOrderByIdAsc();
}

