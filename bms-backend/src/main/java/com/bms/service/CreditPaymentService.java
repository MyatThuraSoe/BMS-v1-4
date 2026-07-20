package com.bms.service;

import com.bms.dto.request.CreditPaymentCreateRequest;
import com.bms.dto.response.CreditPaymentResponse;
import com.bms.entity.CreditPayment;
import com.bms.entity.Customer;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.CreditPaymentRepository;
import com.bms.repository.CustomerRepository;
import com.bms.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@Transactional
public class CreditPaymentService {

    @Autowired
    private CreditPaymentRepository creditPaymentRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private UserRepository userRepository;


    public CreditPaymentResponse createCreditPayment(Long customerId, CreditPaymentCreateRequest request, Long createdByUserId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + customerId));

        BigDecimal currentBalance = customer.getCreditBalance() == null ? BigDecimal.ZERO : customer.getCreditBalance();
        BigDecimal amount = request.getAmount() == null ? BigDecimal.ZERO : request.getAmount();

        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("Credit payment amount must be positive");
        }
        if (amount.compareTo(currentBalance) > 0) {
            throw new BusinessException("Credit payment cannot exceed customer's available credit balance");
        }

        // Apply payment: reduce balance
        BigDecimal newBalance = currentBalance.subtract(amount);
        customer.setCreditBalance(newBalance);

        User createdBy = userRepository.findById(createdByUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + createdByUserId));

        CreditPayment payment = new CreditPayment();
        payment.setCustomer(customer);
        payment.setPaymentDate(LocalDateTime.now());
        payment.setAmount(amount);
        payment.setReference(request.getReference());
        payment.setNotes(request.getNotes());
        payment.setCreatedBy(createdBy);
        payment.setActive(true);

        CreditPayment saved = creditPaymentRepository.save(payment);
        return convertToResponse(saved);
    }

    private CreditPaymentResponse convertToResponse(CreditPayment payment) {
        CreditPaymentResponse response = new CreditPaymentResponse();
        response.setId(payment.getId());
        response.setCustomerId(payment.getCustomer().getId());
        response.setPaymentDate(payment.getPaymentDate());
        response.setAmount(payment.getAmount());
        response.setReference(payment.getReference());
        response.setNotes(payment.getNotes());

        if (payment.getCreatedBy() != null) {
            response.setCreatedBy(payment.getCreatedBy().getId());
            // username not always available; keep null-safe
            try {
                response.setCreatedByUsername(payment.getCreatedBy().getUsername());
            } catch (Exception ignored) {
                // no-op
            }
        }

        response.setIsActive(payment.isActive());
        response.setCreatedAt(payment.getCreatedAt());
        response.setUpdatedAt(payment.getUpdatedAt());
        return response;
    }
}
