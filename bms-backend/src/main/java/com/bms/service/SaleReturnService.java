package com.bms.service;

import com.bms.dto.request.SaleReturnRequest;
import com.bms.dto.request.SalePaymentRequest;
import com.bms.dto.response.PaymentValidationResponse;
import com.bms.dto.response.ReturnableItemsResponse;
import com.bms.dto.response.SaleReturnResponse;
import com.bms.entity.CashDrawerMovement;
import com.bms.entity.CashDrawerSession;
import com.bms.entity.CashShift;
import com.bms.entity.Customer;
import com.bms.entity.DrawerMovementType;
import com.bms.entity.InventoryLedger;
import com.bms.entity.Location;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Product;
import com.bms.entity.ProductPriceHistory;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.SalePayment;
import com.bms.entity.SaleReturn;
import com.bms.entity.SaleReturnItem;
import com.bms.entity.StockMovement;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.CustomerRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.ProductPriceHistoryRepository;
import com.bms.repository.SaleItemRepository;
import com.bms.repository.SalePaymentRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SaleReturnRepository;
import com.bms.repository.StockMovementRepository;
import com.bms.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional(rollbackFor = Exception.class)
public class SaleReturnService {

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private SaleItemRepository saleItemRepository;

    @Autowired
    private SaleReturnRepository saleReturnRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private PaymentValidationService paymentValidationService;

    @Autowired
    private CashDrawerSessionService cashDrawerSessionService;

    @Autowired
    private SalePaymentRepository salePaymentRepository;

    @Autowired
    private LocationService locationService;

    @Autowired
    private ProductPriceHistoryRepository priceHistoryRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private PeriodGuard periodGuard;

    @Autowired
    private ShopTimeService shopTimeService = new ShopTimeService(null, "UTC");

    /**
     * Snapshot of what can still be returned for a sale. Prices come straight
     * off the original SaleItem so the client never computes refund money.
     */
    @Transactional(readOnly = true)
    public ReturnableItemsResponse getReturnableItems(Long saleId) {
        Sale sale = loadActiveSale(saleId);

        BigDecimal outstandingBefore = sale.getTotalAmount() != null ? sale.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal paidBefore = sale.getAmountPaid() != null ? sale.getAmountPaid() : BigDecimal.ZERO;
        BigDecimal returnedBefore = sale.getAmountReturned() != null ? sale.getAmountReturned() : BigDecimal.ZERO;
        outstandingBefore = outstandingBefore.subtract(paidBefore).subtract(returnedBefore).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal totalReturnable = sale.getItems().stream()
                .filter(item -> item.getQuantity() != null && item.getQuantity() > 0)
                .map(item -> {
                    int alreadyReturned = item.getQuantityRefunded() != null ? item.getQuantityRefunded() : 0;
                    int returnable = Math.max(0, item.getQuantity() - alreadyReturned);
                    BigDecimal refund = calculateUnitRefundAmount(sale, item)
                            .multiply(BigDecimal.valueOf(returnable))
                            .setScale(2, RoundingMode.HALF_UP);
                    return refund;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        ReturnableItemsResponse response = new ReturnableItemsResponse();
        response.setSaleId(sale.getId());
        response.setInvoiceNumber(sale.getInvoiceNumber());
        response.setSaleDate(sale.getSaleDate());
        response.setVoided(sale.getIsVoided());
        response.setOutstandingBefore(outstandingBefore);
        response.setEstimatedDebtReduction(sale.getSaleType() == Sale.SaleType.CREDIT
                ? totalReturnable.min(outstandingBefore).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        response.setEstimatedRefundDue(sale.getSaleType() == Sale.SaleType.CREDIT
                ? totalReturnable.subtract(response.getEstimatedDebtReduction()).setScale(2, RoundingMode.HALF_UP)
                : totalReturnable.setScale(2, RoundingMode.HALF_UP));
        response.setItems(sale.getItems().stream()
                .map(item -> convertToReturnableItem(sale, item))
                .collect(Collectors.toList()));
        return response;
    }

    private ReturnableItemsResponse.ReturnableItem convertToReturnableItem(Sale sale, SaleItem item) {
        ReturnableItemsResponse.ReturnableItem dto = new ReturnableItemsResponse.ReturnableItem();
        dto.setSaleItemId(item.getId());
        dto.setProductId(item.getProduct().getId());
        dto.setProductName(item.getProduct().getName());
        dto.setQuantitySold(item.getQuantity());
        int alreadyReturned = item.getQuantityRefunded() != null ? item.getQuantityRefunded() : 0;
        dto.setQuantityAlreadyReturned(alreadyReturned);
        dto.setQuantityReturnable(Math.max(0, item.getQuantity() - alreadyReturned));
        dto.setUnitPrice(item.getUnitPrice());
        dto.setUnitRefundAmount(calculateUnitRefundAmount(sale, item));
        return dto;
    }

    /**
     * Processes a customer return against an existing sale. Supports partial
     * and repeated returns; every line is priced from the ORIGINAL sale prices,
     * stock is restored, and the sale's returnStatus is recomputed from the
     * total returned quantities across all return transactions.
     */
    public SaleReturnResponse createSaleReturn(Long saleId, SaleReturnRequest request, Long userId) {
        periodGuard.assertOpen(shopTimeService.now());
        Sale sale = loadActiveSale(saleId);

        if (sale.getIsVoided() != null && sale.getIsVoided()) {
            throw new BusinessException("Cannot return a voided sale");
        }

        User returnedBy = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        BigDecimal totalBefore = sale.getTotalAmount() != null ? sale.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal paidBefore = sale.getAmountPaid() != null ? sale.getAmountPaid() : BigDecimal.ZERO;
        BigDecimal returnedBefore = sale.getAmountReturned() != null ? sale.getAmountReturned() : BigDecimal.ZERO;
        BigDecimal outstandingBefore = totalBefore.subtract(paidBefore).subtract(returnedBefore)
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        // Was every cent of this invoice already paid before this return?
        // Unpaid credit returns only reduce the debt and never move cash.
        boolean cashSettled = sale.getSaleType() == Sale.SaleType.CREDIT
                && sale.getPaymentStatus() == Sale.PaymentStatus.PAID;

        CashShift employeeShift = paymentValidationService.findOpenShift(userId);

        SaleReturn saleReturn = new SaleReturn();
        saleReturn.setSale(sale);
        saleReturn.setReturnedBy(returnedBy);
        saleReturn.setReturnDate(shopTimeService.now());
        saleReturn.setReason(request.getReason());
        saleReturn.setEmployeeShift(employeeShift);
        saleReturn.setExternalReference(request.getExternalReference());

        BigDecimal totalReturnAmount = BigDecimal.ZERO;
        Set<Long> requestedSaleItemIds = new HashSet<>();
        Map<Long, Integer> returnedQuantitiesBefore = new java.util.HashMap<>();

        for (SaleReturnRequest.SaleReturnItemRequest itemRequest : request.getItems()) {
            if (!requestedSaleItemIds.add(itemRequest.getSaleItemId())) {
                throw new BusinessException("Duplicate return item: " + itemRequest.getSaleItemId());
            }

            // Lock this specific sale item row before reading its returned quantity —
            // prevents two concurrent return requests from both reading "0 already returned"
            // and both approving a return that, combined, exceeds what was actually sold.
            SaleItem saleItem = saleItemRepository.findByIdForUpdate(itemRequest.getSaleItemId())
                    .orElseThrow(() -> new BusinessException("Sale item not found: " + itemRequest.getSaleItemId()));

            if (!saleItem.getSale().getId().equals(saleId)) {
                throw new BusinessException("Sale item does not belong to this sale: " + itemRequest.getSaleItemId());
            }

            int alreadyReturned = saleItem.getQuantityRefunded() != null ? saleItem.getQuantityRefunded() : 0;
            int returnableQuantity = saleItem.getQuantity() - alreadyReturned;
            int requestedQuantity = itemRequest.getQuantity();
            if (requestedQuantity > returnableQuantity) {
                throw new BusinessException("Cannot return " + requestedQuantity + " of " + saleItem.getProduct().getName()
                        + ". Returnable quantity is " + returnableQuantity);
            }

            // Return the tax-inclusive original sale price (tax was charged at sale time,
            // so it must be given back too — otherwise returns silently leak tax). The
            // customer's share of any sale-level discount is deducted first, otherwise a
            // return on a discounted sale refunds more than was actually paid.
            int remainingAfter = returnableQuantity - requestedQuantity;
            BigDecimal returnAmount = SaleDiscountUtils.netItemRevenue(sale, saleItem, returnableQuantity)
                    .subtract(SaleDiscountUtils.netItemRevenue(sale, saleItem, remainingAfter))
                    .add(SaleDiscountUtils.taxForQuantity(saleItem, returnableQuantity)
                            .subtract(SaleDiscountUtils.taxForQuantity(saleItem, remainingAfter)))
                    .setScale(2, RoundingMode.HALF_UP);

            Product product = productRepository.findByIdForUpdate(saleItem.getProduct().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + saleItem.getProduct().getId()));

            // P9: value the returned units at the cost they carried at sale time, not
            // today's average, so inventory value stays consistent if cost moved since.
            SaleReturnItem.ReturnDisposition disposition = resolveDisposition(itemRequest.getDisposition());
            BigDecimal costAtSale = saleItem.getCostPriceAtSale() != null
                    ? saleItem.getCostPriceAtSale() : BigDecimal.ZERO;
            BigDecimal lineValuation = costAtSale.multiply(BigDecimal.valueOf(requestedQuantity))
                    .setScale(CostCalculationUtils.COST_SCALE, RoundingMode.HALF_UP);

            returnedQuantitiesBefore.put(saleItem.getId(), alreadyReturned);
            saleItem.setQuantityRefunded(alreadyReturned + requestedQuantity);

            SaleReturnItem returnItem = new SaleReturnItem();
            returnItem.setSaleReturn(saleReturn);
            returnItem.setSaleItem(saleItem);
            returnItem.setQuantityReturned(requestedQuantity);
            returnItem.setReturnAmount(returnAmount);
            returnItem.setDisposition(disposition);
            saleReturn.getItems().add(returnItem);

            if (disposition == SaleReturnItem.ReturnDisposition.WRITE_OFF) {
                // Unsellable: no stock re-enters. Expense the units at costPriceAtSale
                // through the shared operating-loss path (P3/5.4) so the P&L and the GL
                // journal agree (I4). STOCK_ADJUSTMENT + a recognised loss reason is the
                // exact predicate InventoryLossService filters on.
                StockMovement loss = new StockMovement();
                loss.setProduct(product);
                loss.setMovementType(StockMovement.MovementType.ADJUSTMENT_OUT);
                loss.setQuantity(requestedQuantity);
                loss.setReferenceType(StockMovement.ReferenceType.STOCK_ADJUSTMENT);
                loss.setReferenceId(sale.getId());
                loss.setReasonCode("RETURN_WRITE_OFF");
                loss.setValuationAmount(lineValuation.negate());
                loss.setDescription("Return write-off (unsellable) from sale " + sale.getInvoiceNumber());
                loss.setCreatedBy(returnedBy);
                loss.setMovementDate(shopTimeService.now());
                stockMovementRepository.save(loss);
                ledgerPostingService.postOperatingLoss(loss);
            } else {
                // RESTOCK -> sellable shop stock; QUARANTINE -> owned but not sellable.
                // Both re-enter owned inventory as a receipt at costPriceAtSale, so the
                // weighted average is recomputed over ALL owned units (C1), exactly like
                // a purchase receipt.
                BigDecimal oldCost = product.getCostPrice() != null ? product.getCostPrice() : BigDecimal.ZERO;
                Location quarantine = disposition == SaleReturnItem.ReturnDisposition.QUARANTINE
                        ? locationService.getDefault(Location.LocationType.QUARANTINE) : null;

                // P1/O8: post the valued return BEFORE mutating stock; sole writer of cost.
                InventoryLedger posting = ledgerPostingService.postInventory(
                        product, InventoryLedger.EventType.RETURN, "SALE_RETURN", sale.getId(),
                        saleItem.getId() + ":" + alreadyReturned, requestedQuantity, costAtSale,
                        lineValuation, quarantine != null ? quarantine.getId() : null,
                        "Stock restored from sale return (" + disposition.name() + "): " + sale.getInvoiceNumber(),
                        shopTimeService.now(), returnedBy.getId());
                BigDecimal newCost = posting.getUnitCostAfter();

                String locationCode;
                if (quarantine != null) {
                    locationService.addStock(product, quarantine, requestedQuantity);
                    locationCode = quarantine.getCode();
                } else {
                    int oldStock = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
                    product.setStockQuantity(oldStock + requestedQuantity);
                    locationCode = "SHOP";
                }
                productRepository.save(product);

                if (oldCost.compareTo(newCost) != 0) {
                    ProductPriceHistory history = new ProductPriceHistory();
                    history.setProduct(product);
                    history.setOldPrice(oldCost);
                    history.setNewPrice(newCost);
                    history.setPriceType(ProductPriceHistory.PriceType.COST);
                    history.setSource(ProductPriceHistory.Source.SALE_RETURN);
                    history.setChangedBy(returnedBy);
                    history.setPurchaseQuantity(requestedQuantity);
                    history.setPurchaseUnitPrice(costAtSale);
                    history.setStockQtyAtChange(locationService.totalOwnedUnits(product));
                    history.setInventoryValueDelta(lineValuation);
                    priceHistoryRepository.save(history);
                }

                StockMovement movement = new StockMovement();
                movement.setProduct(product);
                movement.setMovementType(StockMovement.MovementType.ADJUSTMENT_IN);
                movement.setQuantity(requestedQuantity);
                movement.setReferenceType(StockMovement.ReferenceType.RETURN);
                movement.setReferenceId(sale.getId());
                movement.setLocation(locationCode);
                movement.setValuationAmount(lineValuation);
                movement.setDescription("Stock restored from sale return (" + disposition.name() + "): "
                        + sale.getInvoiceNumber());
                movement.setCreatedBy(returnedBy);
                movement.setMovementDate(shopTimeService.now());
                stockMovementRepository.save(movement);
            }

            totalReturnAmount = totalReturnAmount.add(returnAmount);
        }

        BigDecimal debtReduction = BigDecimal.ZERO;
        BigDecimal refundDue = totalReturnAmount;
        if (sale.getSaleType() == Sale.SaleType.CREDIT) {
            debtReduction = totalReturnAmount.min(outstandingBefore).setScale(2, RoundingMode.HALF_UP);
            refundDue = totalReturnAmount.subtract(debtReduction).setScale(2, RoundingMode.HALF_UP);
        }

        PaymentMethod refundMethod = resolveRefundMethod(request.getRefundMethod(), sale, employeeShift, debtReduction, refundDue);
        if (refundMethod == PaymentMethod.CASH && refundDue.compareTo(BigDecimal.ZERO) > 0) {
            if (employeeShift == null) {
                throw new BusinessException(
                        "CASH_REFUND_REQUIRES_DRAWER: Cash refunds require an active cash drawer shift.");
            }
            SalePaymentRequest probe = new SalePaymentRequest();
            probe.setMethod(PaymentMethod.CASH.name());
            probe.setAmount(BigDecimal.ONE);
            PaymentValidationResponse validation = paymentValidationService.validate(
                    employeeShift, List.of(probe));
            if (!validation.isValid()) {
                throw new BusinessException(validation.getCode() + ": "
                        + "Cash refund requires the cash drawer cashier or manager.");
            }
        }

        saleReturn.setRefundMethod(refundMethod);
        saleReturn.setDebtReductionAmount(debtReduction);
        saleReturn.setRefundAmount(refundDue);
        saleReturn.setTotalReturnAmount(totalReturnAmount);
        SaleReturn savedReturn = saleReturnRepository.save(saleReturn);
        for (SaleReturnItem item : savedReturn.getItems()) {
            ledgerPostingService.postReturnProfit(savedReturn, item.getSaleItem(), item.getQuantityReturned(),
                returnedQuantitiesBefore.get(item.getSaleItem().getId()));
        }

        // Cash physically leaves the drawer only for a real cash refund. An
        // unpaid credit return is a balance reduction, not cash.
        if (refundMethod == PaymentMethod.CASH && refundDue.compareTo(BigDecimal.ZERO) > 0) {
            CashDrawerSession session = employeeShift != null ? employeeShift.getDrawerSession() : null;
            CashDrawerMovement movement = cashDrawerSessionService.recordFinancialMovement(
                    session,
                    DrawerMovementType.CASH_REFUND,
                    refundDue.negate(),
                    "SALE_RETURN",
                    savedReturn.getId(),
                    returnedBy,
                    employeeShift,
                    "Cash refund for " + sale.getInvoiceNumber()
                            + (request.getExternalReference() != null
                            ? " | Ref: " + request.getExternalReference() : ""));
            savedReturn.setDrawerMovement(movement);
            savedReturn = saleReturnRepository.save(savedReturn);
        }

        updateSaleReturnStatus(sale);

        // Credit-invoice returns must touch the invoice itself, not just the
        // customer's balance: accumulate the returned amount so the invoice's
        // outstanding balance stays (total - paid - returned). We compute the
        // split from the original pre-return values and apply it once.
        if (sale.getSaleType() == Sale.SaleType.CREDIT) {
            BigDecimal returnedTotal = returnedBefore.add(totalReturnAmount).setScale(2, RoundingMode.HALF_UP);
            sale.setAmountReturned(returnedTotal);

            if (returnedTotal.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal settled = returnedTotal.add(paidBefore);
                if (settled.compareTo(totalBefore) >= 0) {
                    sale.setPaymentStatus(Sale.PaymentStatus.PAID);
                } else if (paidBefore.compareTo(BigDecimal.ZERO) > 0) {
                    sale.setPaymentStatus(Sale.PaymentStatus.PARTIAL);
                } else {
                    sale.setPaymentStatus(Sale.PaymentStatus.UNPAID);
                }
            }
        }
        saleRepository.save(sale);

        // Returns on credit invoices that were UNPAID/PARTIAL should NOT
        // dispense cash — they reduce what the customer owes instead.
        if (sale.getSaleType() == Sale.SaleType.CREDIT) {
            if (!cashSettled) {
                reverseCreditBalance(sale, debtReduction);
            }
            if (refundMethod == PaymentMethod.STORE_CREDIT && refundDue.compareTo(BigDecimal.ZERO) > 0
                    && sale.getCustomer() != null) {
                Customer customer = customerRepository.findByIdForUpdate(sale.getCustomer().getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + sale.getCustomer().getId()));
                customer.setCreditBalance(customer.getCreditBalance()
                        .add(refundDue).setScale(2, RoundingMode.HALF_UP));
                customerRepository.save(customer);
            }
        }

        auditLogService.logAction(userId, "SALE_RETURN",
                "Sale return processed for sale: " + sale.getInvoiceNumber() + ". Amount: " + totalReturnAmount,
                "SaleReturn", savedReturn.getId(), null, savedReturn.toString());

        return convertToResponse(savedReturn);
    }

    /**
     * Prefers the caller's explicit refund method; otherwise uses the debt split
     * to decide whether a credit return is balance-only or a real cash/store-credit refund.
     */
    private PaymentMethod resolveRefundMethod(String raw, Sale sale, CashShift employeeShift,
                                            BigDecimal debtReduction, BigDecimal refundDue) {
        if (sale.getSaleType() == Sale.SaleType.CREDIT && refundDue.compareTo(BigDecimal.ZERO) <= 0) {
            return PaymentMethod.CREDIT;
        }
        if (raw != null && !raw.isBlank()) {
            return paymentValidationService.parseMethod(raw);
        }
        if (sale.getSaleType() == Sale.SaleType.CREDIT) {
            return employeeShift != null ? PaymentMethod.CASH : PaymentMethod.STORE_CREDIT;
        }
        List<SalePayment> payments = salePaymentRepository
                .findBySaleIdOrderByPaidAtAsc(sale.getId());
        if (payments.isEmpty()) {
            return PaymentMethod.CASH;
        }
        Set<PaymentMethod> methods = payments.stream()
                .map(SalePayment::getPaymentMethod)
                .collect(Collectors.toSet());
        if (methods.size() == 1) {
            return methods.iterator().next();
        }
        return methods.contains(PaymentMethod.CASH)
                ? PaymentMethod.CASH : methods.iterator().next();
    }

    /**
     * Recomputes COMPLETED / PARTIALLY_RETURNED / FULLY_RETURNED from the total
     * quantity returned across ALL return transactions on this sale.
     */
    private void updateSaleReturnStatus(Sale sale) {
        long totalSold = 0;
        long totalReturned = 0;
        for (SaleItem item : sale.getItems()) {
            totalSold += item.getQuantity() != null ? item.getQuantity() : 0;
            totalReturned += item.getQuantityRefunded() != null ? item.getQuantityRefunded() : 0;
        }
        if (totalSold > 0 && totalReturned >= totalSold) {
            sale.setReturnStatus(Sale.ReturnStatus.FULLY_RETURNED);
        } else if (totalReturned > 0) {
            sale.setReturnStatus(Sale.ReturnStatus.PARTIALLY_RETURNED);
        } else {
            sale.setReturnStatus(Sale.ReturnStatus.COMPLETED);
        }
        saleRepository.save(sale);
    }

    private void reverseCreditBalance(Sale sale, BigDecimal debtReduction) {
        if (sale.getCustomer() == null || debtReduction == null || debtReduction.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        com.bms.entity.Customer creditCustomer = customerRepository.findByIdForUpdate(sale.getCustomer().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + sale.getCustomer().getId()));

        BigDecimal newBalance = creditCustomer.getCurrentBalance().subtract(debtReduction)
                .setScale(2, RoundingMode.HALF_UP);
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            // O1: a return worth more than the remaining debt becomes customer
            // credit instead of being silently dropped.
            creditCustomer.setCreditBalance(creditCustomer.getCreditBalance()
                    .add(newBalance.negate()).setScale(2, RoundingMode.HALF_UP));
            newBalance = BigDecimal.ZERO;
        }
        creditCustomer.setCurrentBalance(newBalance);
        customerRepository.save(creditCustomer);
    }

    /**
     * Tax-inclusive per-unit refund derived ONLY from the stored sale prices,
     * less this line's allocated share of the sale-level discount.
     */
    private BigDecimal calculateUnitRefundAmount(Sale sale, SaleItem saleItem) {
        BigDecimal itemTotal = saleItem.getTotalPrice() != null ? saleItem.getTotalPrice() : BigDecimal.ZERO;
        BigDecimal itemTax = saleItem.getTaxAmount() != null ? saleItem.getTaxAmount() : BigDecimal.ZERO;
        int itemQty = saleItem.getQuantity() != null && saleItem.getQuantity() > 0 ? saleItem.getQuantity() : 1;
        BigDecimal allocatedDiscount = SaleDiscountUtils.discountForItem(sale, saleItem);
        return SaleDiscountUtils.unitRefundAmount(itemTotal, itemTax, allocatedDiscount, itemQty);
    }

    /** P9: blank/absent disposition keeps the historical RESTOCK behaviour. */
    private SaleReturnItem.ReturnDisposition resolveDisposition(String raw) {
        if (raw == null || raw.isBlank()) {
            return SaleReturnItem.ReturnDisposition.RESTOCK;
        }
        try {
            return SaleReturnItem.ReturnDisposition.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Unknown return disposition: " + raw);
        }
    }

    private Sale loadActiveSale(Long saleId) {
        Sale sale = saleRepository.findByIdForUpdate(saleId)
                .orElseGet(() -> saleRepository.findById(saleId)
                        .orElseThrow(() -> new ResourceNotFoundException("Sale not found: " + saleId)));
        if (!sale.getIsActive() || sale.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Sale not found: " + saleId);
        }
        return sale;
    }

    @Transactional(readOnly = true)
    public SaleReturnResponse getReturnById(Long id) {
        SaleReturn saleReturn = saleReturnRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sale return not found: " + id));
        return convertToResponse(saleReturn);
    }

    @Transactional(readOnly = true)
    public List<SaleReturnResponse> getReturnsForSale(Long saleId) {
        return saleReturnRepository.findBySaleIdOrderByReturnDateDesc(saleId).stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<SaleReturnResponse> getAllReturns(Long saleId, String invoice, Pageable pageable) {
        Page<SaleReturn> page = saleReturnRepository.findFilteredReturns(
                saleId,
                (invoice != null && !invoice.isBlank()) ? invoice : null,
                pageable);
        return page.map(this::convertToResponse);
    }

    public SaleReturnResponse convertToResponse(SaleReturn saleReturn) {
        SaleReturnResponse response = new SaleReturnResponse();
        response.setId(saleReturn.getId());
        response.setSaleId(saleReturn.getSale().getId());
        response.setInvoiceNumber(saleReturn.getSale().getInvoiceNumber());
        response.setReturnedBy(saleReturn.getReturnedBy().getId());
        response.setReturnedByUsername(saleReturn.getReturnedBy().getUsername());
        response.setReturnDate(saleReturn.getReturnDate());
        response.setReason(saleReturn.getReason());
        response.setTotalReturnAmount(saleReturn.getTotalReturnAmount());
        response.setDebtReductionAmount(saleReturn.getDebtReductionAmount());
        response.setRefundAmount(saleReturn.getRefundAmount());
        response.setRefundMethod(saleReturn.getRefundMethod().name());
        if (saleReturn.getEmployeeShift() != null) {
            response.setEmployeeShiftId(saleReturn.getEmployeeShift().getId());
        }
        if (saleReturn.getDrawerMovement() != null) {
            response.setDrawerMovementId(saleReturn.getDrawerMovement().getId());
        }
        response.setItems(saleReturn.getItems().stream().map(item -> {
            SaleReturnResponse.SaleReturnItemResponse itemResponse = new SaleReturnResponse.SaleReturnItemResponse();
            itemResponse.setId(item.getId());
            itemResponse.setSaleItemId(item.getSaleItem().getId());
            itemResponse.setProductId(item.getSaleItem().getProduct().getId());
            itemResponse.setProductName(item.getSaleItem().getProduct().getName());
            itemResponse.setQuantityReturned(item.getQuantityReturned());
            itemResponse.setReturnAmount(item.getReturnAmount());
            itemResponse.setDisposition(item.getDisposition() != null
                    ? item.getDisposition().name() : SaleReturnItem.ReturnDisposition.RESTOCK.name());
            return itemResponse;
        }).collect(Collectors.toList()));
        return response;
    }

    // Batch map a page of sales' returns so DTOs build in a single query instead of one per sale.
    public Map<Long, List<SaleReturn>> findBySaleIds(List<Long> saleIds) {
        return saleIds.isEmpty()
                ? Map.of()
                : saleReturnRepository.findBySaleIdInOrderByReturnDateDesc(saleIds).stream()
                        .collect(Collectors.groupingBy(r -> r.getSale().getId()));
    }

    @Transactional(readOnly = true)
    public List<SaleReturn> findBySaleId(Long saleId) {
        return saleReturnRepository.findBySaleIdOrderByReturnDateDesc(saleId);
    }
}
