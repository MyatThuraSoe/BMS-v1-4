package com.bms.repository;

import com.bms.entity.PurchaseExpense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PurchaseExpenseRepository extends JpaRepository<PurchaseExpense, Long> {
    List<PurchaseExpense> findByPurchaseIdOrderByIdAsc(Long purchaseId);
    void deleteByPurchaseId(Long purchaseId);
}
