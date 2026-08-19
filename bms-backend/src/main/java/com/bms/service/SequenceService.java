package com.bms.service;

import com.bms.entity.InvoiceSequence;
import com.bms.entity.PurchaseSequence;
import com.bms.exception.BusinessException;
import com.bms.repository.InvoiceSequenceRepository;
import com.bms.repository.PurchaseSequenceRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Generates invoice / purchase-order numbers atomically using a per-day
 * sequence row locked with PESSIMISTIC_WRITE. Two concurrent requests can no
 * longer read the same "lastNumber + 1" and produce duplicate numbers.
 */
@Service
public class SequenceService {

    private final InvoiceSequenceRepository invoiceSequenceRepository;
    private final PurchaseSequenceRepository purchaseSequenceRepository;

    public SequenceService(InvoiceSequenceRepository invoiceSequenceRepository,
                           PurchaseSequenceRepository purchaseSequenceRepository) {
        this.invoiceSequenceRepository = invoiceSequenceRepository;
        this.purchaseSequenceRepository = purchaseSequenceRepository;
    }

    @Transactional
    public String nextInvoiceNumber() {
        LocalDate today = LocalDate.now();
        InvoiceSequence seq = lockOrCreateInvoice(today);
        int next = (seq.getLastNumber() == null ? 0 : seq.getLastNumber()) + 1;
        seq.setLastNumber(next);
        return "INV" + today.format(DateTimeFormatter.ofPattern("yyMMdd"))
                + String.format("%03d", next);
    }

    /**
     * Credit invoices share the SAME locked per-day sequence row as cash
     * invoices (prefixes differ so numbers never collide) — e.g.
     * CR-20260819-001. Keeps the atomic lock guarantee and a single
     * chronological counter for easy reporting.
     */
    @Transactional
    public String nextCreditInvoiceNumber() {
        LocalDate today = LocalDate.now();
        InvoiceSequence seq = lockOrCreateInvoice(today);
        int next = (seq.getLastNumber() == null ? 0 : seq.getLastNumber()) + 1;
        seq.setLastNumber(next);
        return "CR-" + today.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%03d", next);
    }

    @Transactional
    public String nextPurchaseNumber() {
        LocalDate today = LocalDate.now();
        PurchaseSequence seq = lockOrCreatePurchase(today);
        int next = (seq.getLastNumber() == null ? 0 : seq.getLastNumber()) + 1;
        seq.setLastNumber(next);
        return "PO-" + today.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%04d", next);
    }

    private InvoiceSequence lockOrCreateInvoice(LocalDate today) {
        return invoiceSequenceRepository.findByDateForUpdate(today)
                .orElseGet(() -> {
                    try {
                        InvoiceSequence fresh = new InvoiceSequence();
                        fresh.setSequenceDate(today);
                        fresh.setLastNumber(0);
                        return invoiceSequenceRepository.saveAndFlush(fresh);
                    } catch (DataIntegrityViolationException ex) {
                        // Another request created today's row first — re-read it under the lock
                        return invoiceSequenceRepository.findByDateForUpdate(today)
                                .orElseThrow(() -> new BusinessException("Failed to acquire invoice sequence"));
                    }
                });
    }

    private PurchaseSequence lockOrCreatePurchase(LocalDate today) {
        return purchaseSequenceRepository.findByDateForUpdate(today)
                .orElseGet(() -> {
                    try {
                        PurchaseSequence fresh = new PurchaseSequence();
                        fresh.setSequenceDate(today);
                        fresh.setLastNumber(0);
                        return purchaseSequenceRepository.saveAndFlush(fresh);
                    } catch (DataIntegrityViolationException ex) {
                        return purchaseSequenceRepository.findByDateForUpdate(today)
                                .orElseThrow(() -> new BusinessException("Failed to acquire purchase sequence"));
                    }
                });
    }
}