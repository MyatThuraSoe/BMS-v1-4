package com.bms.repository;

import com.bms.entity.PriceListItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PriceListItemRepository extends JpaRepository<PriceListItem, Long> {
    List<PriceListItem> findByPriceListId(Long priceListId);
    Optional<PriceListItem> findByPriceListIdAndProductId(Long priceListId, Long productId);
    void deleteByPriceListId(Long priceListId);
}
