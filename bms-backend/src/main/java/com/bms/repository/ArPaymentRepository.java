package com.bms.repository;

import com.bms.entity.ArPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ArPaymentRepository extends JpaRepository<ArPayment, Long> {

    @EntityGraph(attributePaths = {"invoice", "recordedBy"})
    List<ArPayment> findByInvoiceIdOrderByPaymentDateAsc(@Param("invoiceId") Long invoiceId);
}