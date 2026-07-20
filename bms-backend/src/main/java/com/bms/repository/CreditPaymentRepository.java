package com.bms.repository;

import com.bms.entity.CreditPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CreditPaymentRepository extends JpaRepository<CreditPayment, Long> {

    List<CreditPayment> findByCustomerIdAndIsActiveTrue(Long customerId);
}
