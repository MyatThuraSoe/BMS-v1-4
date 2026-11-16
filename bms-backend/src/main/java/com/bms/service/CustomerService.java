package com.bms.service;

import com.bms.dto.request.CustomerCreateRequest;
import com.bms.dto.response.CustomerResponse;
import com.bms.entity.Customer;
import com.bms.entity.CustomerPhone;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.CustomerRepository;
import com.bms.repository.OrderRepository;
import com.bms.repository.SaleRepository;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class CustomerService {

    @Autowired
    private ShopTimeService shopTimeService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private SequenceService sequenceService;

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private OrderRepository orderRepository;

    public Page<CustomerResponse> getAllCustomers(Pageable pageable) {
        return customerRepository.findActiveCustomers(pageable).map(this::convertToResponse);
    }

    public Page<CustomerResponse> searchCustomers(String keyword, String city, Pageable pageable) {
        return customerRepository.searchActiveCustomers(keyword, city, pageable).map(this::convertToResponse);
    }

    public List<String> getDistinctCities() {
        return customerRepository.findDistinctActiveCities();
    }

    public CustomerResponse getCustomerById(Long id) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id));
        if (!customer.getIsActive() || customer.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Customer not found: " + id);
        }
        return convertToResponse(customer);
    }

    public Customer createCustomer(CustomerCreateRequest request, Long userId) {
        String customerCode = generateCustomerCode();

        if (request.getEmail() != null && !request.getEmail().isBlank() && customerRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("Customer with email '" + request.getEmail() + "' already exists");
        }
        List<String> phones = normalizePhones(request.getPhones(), request.getPhone());
        for (String p : phones) {
            if (customerRepository.existsByPhone(p)) {
                throw new BusinessException("Customer with phone '" + p + "' already exists");
            }
        }

        Customer customer = new Customer();
        customer.setCustomerCode(customerCode);
        customer.setFirstName(request.getFirstName());
        customer.setLastName(request.getLastName());
        customer.setEmail(request.getEmail());
        fillPhones(customer, phones);
        customer.setAddress(request.getAddress());
        customer.setCity(request.getCity());
        customer.setState(request.getState());
        customer.setZipCode(request.getZipCode());
        customer.setCountry(request.getCountry());
        customer.setNotes(request.getNotes());
        customer.setCreditLimit(request.getCreditLimit() != null
                ? request.getCreditLimit().setScale(2, java.math.RoundingMode.HALF_UP)
                : java.math.BigDecimal.ZERO);

        Customer savedCustomer = customerRepository.save(customer);

        auditLogService.logAction(userId, "CUSTOMER_CREATE", 
            "Customer created: " + savedCustomer.getFirstName() + " " + savedCustomer.getLastName(), 
            "Customer", savedCustomer.getId(), null, savedCustomer.toString());

        return savedCustomer;
    }


    public Customer updateCustomer(Long id, CustomerCreateRequest request, Long userId) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id));
        if (!customer.getIsActive() || customer.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Customer not found: " + id);
        }

        String oldValues = customer.toString();

        if (request.getEmail() != null && !request.getEmail().isBlank()
                && !request.getEmail().equals(customer.getEmail())
                && customerRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("Customer with email '" + request.getEmail() + "' already exists");
        }
        Set<String> currentPhones = customer.getPhones().stream()
                .map(CustomerPhone::getPhone)
                .collect(Collectors.toSet());
        List<String> phones = normalizePhones(request.getPhones(), request.getPhone());
        for (String p : phones) {
            if (!currentPhones.contains(p) && customerRepository.existsByPhone(p)) {
                throw new BusinessException("Customer with phone '" + p + "' already exists");
            }
        }

        customer.setFirstName(request.getFirstName());
        customer.setLastName(request.getLastName());
        customer.setEmail(request.getEmail() != null && !request.getEmail().isBlank() ? request.getEmail() : null);
        fillPhones(customer, phones);
        customer.setIsQuickAdd(false);
        customer.setAddress(request.getAddress());
        customer.setCity(request.getCity());
        customer.setState(request.getState());
        customer.setZipCode(request.getZipCode());
        customer.setCountry(request.getCountry());
        customer.setNotes(request.getNotes());
        if (request.getCreditLimit() != null) {
            customer.setCreditLimit(request.getCreditLimit().setScale(2, java.math.RoundingMode.HALF_UP));
        }

        Customer updatedCustomer = customerRepository.save(customer);

        auditLogService.logAction(userId, "CUSTOMER_UPDATE",
            "Customer updated: " + updatedCustomer.getFirstName() + " " + updatedCustomer.getLastName(),
            "Customer", updatedCustomer.getId(), oldValues, updatedCustomer.toString());

        return updatedCustomer;
    }

    public void deleteCustomer(Long id, Long userId) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id));

        BigDecimal currentBalance = customer.getCurrentBalance();
        if (currentBalance != null && currentBalance.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("Cannot delete customer with outstanding balance: " + currentBalance);
        }
        BigDecimal creditBalance = customer.getCreditBalance();
        if (creditBalance != null && creditBalance.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("Cannot delete customer with store credit remaining: " + creditBalance);
        }
        // T3: the stored balance is only as good as the invoices behind it. A
        // hidden customer with an open invoice makes the debt uncollectable from
        // the UI while it still counts in every AR total.
        BigDecimal openInvoices = saleRepository.sumOutstandingArByCustomerId(id);
        if (openInvoices != null && openInvoices.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("Cannot delete customer with unpaid credit invoices ("
                    + openInvoices + " outstanding)");
        }
        long pendingOrders = orderRepository.countPendingOrdersByCustomerId(id);
        if (pendingOrders > 0) {
            throw new BusinessException("Cannot delete customer with " + pendingOrders
                    + " pending order(s); cancel or convert them first");
        }

        customer.setCustomerCode(com.bms.util.SoftDeleteKeys.release(customer.getCustomerCode(), customer.getId(), 255));
        customer.setDeletedAt(shopTimeService.now());
        customer.setIsActive(false);
        customerRepository.save(customer);

        auditLogService.logAction(userId, "CUSTOMER_DELETE",
            "Customer deleted: " + customer.getFirstName() + " " + customer.getLastName(),
            "Customer", customer.getId(), customer.toString(), null);
    }

    private String generateCustomerCode() {
        return sequenceService.nextCustomerNumber();
    }

    private CustomerResponse convertToResponse(Customer customer) {
        CustomerResponse response = new CustomerResponse();
        response.setId(customer.getId());
        response.setCustomerCode(customer.getCustomerCode());
        response.setFirstName(customer.getFirstName());
        response.setLastName(customer.getLastName());
        response.setEmail(customer.getEmail());
        response.setPhone(customer.getPhone());
        response.setPhones(customer.getPhones().stream()
                .map(CustomerPhone::getPhone)
                .collect(Collectors.toList()));
        response.setAddress(customer.getAddress());
        response.setCity(customer.getCity());
        response.setState(customer.getState());
        response.setZipCode(customer.getZipCode());
        response.setCountry(customer.getCountry());
        response.setNotes(customer.getNotes());
        response.setIsActive(customer.getIsActive());
        response.setIsQuickAdd(customer.getIsQuickAdd());
        response.setCreditLimit(customer.getCreditLimit());
        response.setCurrentBalance(customer.getCurrentBalance());
        response.setCreditBalance(customer.getCreditBalance());
        response.setCreatedAt(customer.getCreatedAt());
        response.setUpdatedAt(customer.getUpdatedAt());
        return response;
    }

    private List<String> normalizePhones(List<String> phones, String primary) {
        List<String> result = new ArrayList<>();
        if (phones != null) {
            for (String p : phones) {
                if (p != null && !p.isBlank() && !result.contains(p.trim())) {
                    result.add(p.trim());
                }
            }
        }
        if (primary != null && !primary.isBlank() && !result.contains(primary.trim())) {
            result.add(0, primary.trim());
        }
        return result;
    }

    private void fillPhones(Customer customer, List<String> phones) {
        customer.getPhones().clear();
        for (String p : phones) {
            CustomerPhone cp = new CustomerPhone();
            cp.setCustomer(customer);
            cp.setPhone(p);
            customer.getPhones().add(cp);
        }
        customer.setPhone(phones.isEmpty() ? null : phones.get(0));
    }
}
