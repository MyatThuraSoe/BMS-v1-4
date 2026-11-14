package com.bms.service;

import com.bms.entity.InvoiceSequence;
import com.bms.entity.OrderSequence;
import com.bms.entity.PurchaseSequence;
import com.bms.exception.BusinessException;
import com.bms.repository.InvoiceSequenceRepository;
import com.bms.repository.OrderSequenceRepository;
import com.bms.repository.PurchaseSequenceRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Generates invoice / purchase-order / order numbers atomically using a
 * per-day sequence row locked with PESSIMISTIC_WRITE. Two concurrent requests
 * can no longer read the same "lastNumber + 1" and produce duplicate numbers.
 */
@Service
public class SequenceService {

    private final InvoiceSequenceRepository invoiceSequenceRepository;
    private final PurchaseSequenceRepository purchaseSequenceRepository;
    private final OrderSequenceRepository orderSequenceRepository;
    private final ShopTimeService shopTimeService;

    public SequenceService(InvoiceSequenceRepository invoiceSequenceRepository,
                           PurchaseSequenceRepository purchaseSequenceRepository,
                           OrderSequenceRepository orderSequenceRepository,
                           ShopTimeService shopTimeService) {
        this.invoiceSequenceRepository = invoiceSequenceRepository;
        this.purchaseSequenceRepository = purchaseSequenceRepository;
        this.orderSequenceRepository = orderSequenceRepository;
        this.shopTimeService = shopTimeService;
    }

    @Transactional
    public String nextInvoiceNumber() {
        // O7: per-day sequence keys roll over at midnight in the shop timezone.
        LocalDate today = shopTimeService.today();
        InvoiceSequence seq = lockOrCreateInvoice(today);
        int next = (seq.getLastNumber() == null ? 0 : seq.getLastNumber()) + 1;
        seq.setLastNumber(next);
        return "INV" + today.format(DateTimeFormatter.ofPattern("yyMMdd"))
                + String.format("%05d", next);
    }

    /**
     * Credit invoices share the SAME locked per-day sequence row as cash
     * invoices (prefixes differ so numbers never collide) — e.g.
     * CR-20260819-001. Keeps the atomic lock guarantee and a single
     * chronological counter for easy reporting.
     */
    @Transactional
    public String nextCreditInvoiceNumber() {
        // O7: per-day sequence keys roll over at midnight in the shop timezone.
        LocalDate today = shopTimeService.today();
        InvoiceSequence seq = lockOrCreateInvoice(today);
        int next = (seq.getLastNumber() == null ? 0 : seq.getLastNumber()) + 1;
        seq.setLastNumber(next);
        return "CR-" + today.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%05d", next);
    }

    @Transactional
    public String nextPurchaseNumber() {
        // O7: per-day sequence keys roll over at midnight in the shop timezone.
        LocalDate today = shopTimeService.today();
        PurchaseSequence seq = lockOrCreatePurchase(today);
        int next = (seq.getLastNumber() == null ? 0 : seq.getLastNumber()) + 1;
        seq.setLastNumber(next);
        return "PO-" + today.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%04d", next);
    }

    @Transactional
    public String nextOrderNumber() {
        // O7: per-day sequence keys roll over at midnight in the shop timezone.
        LocalDate today = shopTimeService.today();
        OrderSequence seq = lockOrCreateOrder(today);
        int next = (seq.getLastNumber() == null ? 0 : seq.getLastNumber()) + 1;
        seq.setLastNumber(next);
        return "ORD-" + today.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%03d", next);
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

    private OrderSequence lockOrCreateOrder(LocalDate today) {
        return orderSequenceRepository.findByDateForUpdate(today)
                .orElseGet(() -> {
                    try {
                        OrderSequence fresh = new OrderSequence();
                        fresh.setSequenceDate(today);
                        fresh.setLastNumber(0);
                        return orderSequenceRepository.saveAndFlush(fresh);
                    } catch (DataIntegrityViolationException ex) {
                        return orderSequenceRepository.findByDateForUpdate(today)
                                .orElseThrow(() -> new BusinessException("Failed to acquire order sequence"));
                    }
                });
    }
}