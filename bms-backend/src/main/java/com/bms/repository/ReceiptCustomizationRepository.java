package com.bms.repository;

import com.bms.entity.ReceiptCustomization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReceiptCustomizationRepository extends JpaRepository<ReceiptCustomization, Long> {
    Optional<ReceiptCustomization> findTopByOrderByIdAsc();
}
