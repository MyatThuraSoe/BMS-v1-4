package com.bms.repository;

import com.bms.entity.SaleReturnItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SaleReturnItemRepository extends JpaRepository<SaleReturnItem, Long> {
}
