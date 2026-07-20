package com.bms.service;

import com.bms.dto.request.CustomerCreateRequest;
import com.bms.dto.response.CustomerResponse;
import com.bms.entity.Customer;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.CustomerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opencsv.CSVWriterBuilder;
import com.opencsv.ICSVWriter;

import java.io.Writer;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Transactional
public class CustomerService {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AuditLogService auditLogService;

    public Page<CustomerResponse> getAllCustomers(Pageable pageable) {
        return customerRepository.findActiveCustomers(pageable).map(this::convertToResponse);
    }

    public Page<CustomerResponse> searchCustomers(String keyword, Pageable pageable) {
        return customerRepository.searchActiveCustomers(keyword, pageable).map(this::convertToResponse);
    }

    public CustomerResponse getCustomerById(Long id) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id));
        if (!customer.getIsActive() || customer.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Customer not found: " + id);
        }
        return convertToResponse(customer);
    }

    public Customer createCustomer(CustomerCreateRequest request) {
        String customerCode = generateCustomerCode();

        if (customerRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("Customer with email '" + request.getEmail() + "' already exists");
        }
        if (customerRepository.existsByPhone(request.getPhone())) {
            throw new BusinessException("Customer with phone '" + request.getPhone() + "' already exists");
        }

        Customer customer = new Customer();
        customer.setCustomerCode(customerCode);
        customer.setFirstName(request.getFirstName());
        customer.setLastName(request.getLastName());
        customer.setEmail(request.getEmail());
        customer.setPhone(request.getPhone());
        customer.setAddress(request.getAddress());
        customer.setCity(request.getCity());
        customer.setState(request.getState());
        customer.setZipCode(request.getZipCode());
        customer.setCountry(request.getCountry());
        customer.setNotes(request.getNotes());

        Customer savedCustomer = customerRepository.save(customer);

        auditLogService.logAction(null, "CUSTOMER_CREATE", 
            "Customer created: " + savedCustomer.getFirstName() + " " + savedCustomer.getLastName(), 
            "Customer", savedCustomer.getId(), null, savedCustomer.toString());

        return savedCustomer;
    }

    public Customer updateCustomer(Long id, CustomerCreateRequest request) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id));
        if (!customer.getIsActive() || customer.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Customer not found: " + id);
        }

        String oldValues = customer.toString();

        if (!customer.getEmail().equals(request.getEmail()) && customerRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("Customer with email '" + request.getEmail() + "' already exists");
        }
        if (!customer.getPhone().equals(request.getPhone()) && customerRepository.existsByPhone(request.getPhone())) {
            throw new BusinessException("Customer with phone '" + request.getPhone() + "' already exists");
        }

        customer.setFirstName(request.getFirstName());
        customer.setLastName(request.getLastName());
        customer.setEmail(request.getEmail());
        customer.setPhone(request.getPhone());
        customer.setAddress(request.getAddress());
        customer.setCity(request.getCity());
        customer.setState(request.getState());
        customer.setZipCode(request.getZipCode());
        customer.setCountry(request.getCountry());
        customer.setNotes(request.getNotes());

        Customer updatedCustomer = customerRepository.save(customer);

        auditLogService.logAction(null, "CUSTOMER_UPDATE", 
            "Customer updated: " + updatedCustomer.getFirstName() + " " + updatedCustomer.getLastName(), 
            "Customer", updatedCustomer.getId(), oldValues, updatedCustomer.toString());

        return updatedCustomer;
    }

    public void deleteCustomer(Long id) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id));
        
        customer.setDeletedAt(LocalDateTime.now());
        customer.setIsActive(false);
        customerRepository.save(customer);

        auditLogService.logAction(null, "CUSTOMER_DELETE", 
            "Customer deleted: " + customer.getFirstName() + " " + customer.getLastName(), 
            "Customer", customer.getId(), customer.toString(), null);
    }

private String generateCustomerCode() {
        String prefix = "CUST-";
        String datePart = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        
        Optional<Customer> lastCustomerOpt = customerRepository.findAll(
            org.springframework.data.domain.PageRequest.of(0, 1, org.springframework.data.domain.Sort.by("customerCode").descending())
        ).stream()
            .filter(c -> c.getCustomerCode() != null && c.getCustomerCode().startsWith(prefix + datePart))
            .findFirst();
        
        String lastCode = lastCustomerOpt.map(Customer::getCustomerCode)
                .orElse(prefix + datePart + "-0000");
        
        int seqNum = 1;
        if (lastCode.contains("-")) {
            try {
                seqNum = Integer.parseInt(lastCode.substring(lastCode.lastIndexOf("-") + 1)) + 1;
            } catch (NumberFormatException e) {
                seqNum = 1;
            }
        }
        
        return prefix + datePart + "-" + String.format("%04d", seqNum);
    }

    public void exportCustomersToCsv(Writer writer) {
        Page<Customer> customers = customerRepository.findActiveCustomers(org.springframework.data.domain.Pageable.unpaged());
        ICSVWriter csvWriter = new CSVWriterBuilder(writer)
                .withSeparator(',')
                .build();

        csvWriter.writeNext(new String[]{
                "Customer Code", "First Name", "Last Name", "Email", "Phone",
                "Address", "City", "State", "Zip Code", "Country",
                "Credit Balance", "Credit Limit", "Notes"
        });

        for (Customer customer : customers) {
            csvWriter.writeNext(new String[]{
                    customer.getCustomerCode() != null ? customer.getCustomerCode() : "",
                    customer.getFirstName() != null ? customer.getFirstName() : "",
                    customer.getLastName() != null ? customer.getLastName() : "",
                    customer.getEmail() != null ? customer.getEmail() : "",
                    customer.getPhone() != null ? customer.getPhone() : "",
                    customer.getAddress() != null ? customer.getAddress() : "",
                    customer.getCity() != null ? customer.getCity() : "",
                    customer.getState() != null ? customer.getState() : "",
                    customer.getZipCode() != null ? customer.getZipCode() : "",
                    customer.getCountry() != null ? customer.getCountry() : "",
                    customer.getCreditBalance() != null ? customer.getCreditBalance().toPlainString() : "0.00",
                    customer.getCreditLimit() != null ? customer.getCreditLimit().toPlainString() : "0.00",
                    customer.getNotes() != null ? customer.getNotes() : ""
            });
        }
    }

    private CustomerResponse convertToResponse(Customer customer) {
        CustomerResponse response = new CustomerResponse();
        response.setId(customer.getId());
        response.setCustomerCode(customer.getCustomerCode());
        response.setFirstName(customer.getFirstName());
        response.setLastName(customer.getLastName());
        response.setEmail(customer.getEmail());
        response.setPhone(customer.getPhone());
        response.setAddress(customer.getAddress());
        response.setCity(customer.getCity());
        response.setState(customer.getState());
        response.setZipCode(customer.getZipCode());
        response.setCountry(customer.getCountry());
        response.setNotes(customer.getNotes());
        response.setCreditBalance(customer.getCreditBalance());
        response.setCreditLimit(customer.getCreditLimit());
        response.setIsActive(customer.getIsActive());
        response.setCreatedAt(customer.getCreatedAt());
        response.setUpdatedAt(customer.getUpdatedAt());
        return response;
    }
}
