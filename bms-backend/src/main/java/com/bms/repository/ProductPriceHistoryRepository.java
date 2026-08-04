package com.bms.repository;

import com.bms.entity.ProductPriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductPriceHistoryRepository extends JpaRepository<ProductPriceHistory, Long> {

    List<ProductPriceHistory> findByProductIdOrderByChangedAtDesc(Long productId);
}
