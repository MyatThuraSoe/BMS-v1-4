package com.bms.service;

import com.bms.dto.request.SupplierCreateRequest;
import com.bms.dto.response.SupplierResponse;
import com.bms.entity.Supplier;
import com.bms.entity.SupplierPhone;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.SupplierRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class SupplierService {

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private AuditLogService auditLogService;

    public Page<SupplierResponse> getAllSuppliers(Pageable pageable) {
        return supplierRepository.findActiveSuppliers(pageable).map(this::convertToResponse);
    }

    public Page<SupplierResponse> searchSuppliers(String keyword, Pageable pageable) {
        return supplierRepository.searchActiveSuppliers(keyword, pageable).map(this::convertToResponse);
    }

    public SupplierResponse getSupplierById(Long id) {
        Supplier supplier = supplierRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found: " + id));
        if (!supplier.getIsActive() || supplier.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Supplier not found: " + id);
        }
        return convertToResponse(supplier);
    }

    public Supplier createSupplier(SupplierCreateRequest request) {
        if (supplierRepository.existsByName(request.getName())) {
            throw new BusinessException("Supplier with name '" + request.getName() + "' already exists");
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()
                && supplierRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("Supplier with email '" + request.getEmail() + "' already exists");
        }
        List<String> phones = normalizePhones(request.getPhones(), request.getPhone());
        for (String p : phones) {
            if (supplierRepository.existsByPhone(p)) {
                throw new BusinessException("Supplier with phone '" + p + "' already exists");
            }
        }

        Supplier supplier = new Supplier();
        supplier.setName(request.getName());
        supplier.setContactPerson(request.getContactPerson());
        supplier.setEmail(request.getEmail() != null && !request.getEmail().isBlank() ? request.getEmail() : null);
        fillPhones(supplier, phones);
        supplier.setAddress(request.getAddress());
        supplier.setTaxId(request.getTaxId());
        supplier.setPaymentTerms(request.getPaymentTerms());
        supplier.setNotes(request.getNotes());

        Supplier savedSupplier = supplierRepository.save(supplier);

        auditLogService.logAction(null, "SUPPLIER_CREATE", 
            "Supplier created: " + savedSupplier.getName(), 
            "Supplier", savedSupplier.getId(), null, savedSupplier.toString());

        return savedSupplier;
    }

    public Supplier updateSupplier(Long id, SupplierCreateRequest request) {
        Supplier supplier = supplierRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found: " + id));
        if (!supplier.getIsActive() || supplier.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Supplier not found: " + id);
        }

        String oldValues = supplier.toString();

        if (!supplier.getName().equals(request.getName()) && supplierRepository.existsByName(request.getName())) {
            throw new BusinessException("Supplier with name '" + request.getName() + "' already exists");
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()
                && !request.getEmail().equals(supplier.getEmail())
                && supplierRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("Supplier with email '" + request.getEmail() + "' already exists");
        }
        Set<String> currentPhones = supplier.getPhones().stream()
                .map(SupplierPhone::getPhone)
                .collect(Collectors.toSet());
        List<String> phones = normalizePhones(request.getPhones(), request.getPhone());
        for (String p : phones) {
            if (!currentPhones.contains(p) && supplierRepository.existsByPhone(p)) {
                throw new BusinessException("Supplier with phone '" + p + "' already exists");
            }
        }

        supplier.setName(request.getName());
        supplier.setContactPerson(request.getContactPerson());
        supplier.setEmail(request.getEmail() != null && !request.getEmail().isBlank() ? request.getEmail() : null);
        fillPhones(supplier, phones);
        supplier.setAddress(request.getAddress());
        supplier.setTaxId(request.getTaxId());
        supplier.setPaymentTerms(request.getPaymentTerms());
        supplier.setNotes(request.getNotes());

        Supplier updatedSupplier = supplierRepository.save(supplier);

        auditLogService.logAction(null, "SUPPLIER_UPDATE", 
            "Supplier updated: " + updatedSupplier.getName(), 
            "Supplier", updatedSupplier.getId(), oldValues, updatedSupplier.toString());

        return updatedSupplier;
    }

    public void deleteSupplier(Long id) {
        Supplier supplier = supplierRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found: " + id));
        
        supplier.setDeletedAt(LocalDateTime.now());
        supplier.setIsActive(false);
        supplierRepository.save(supplier);

        auditLogService.logAction(null, "SUPPLIER_DELETE", 
            "Supplier deleted: " + supplier.getName(), 
            "Supplier", supplier.getId(), supplier.toString(), null);
    }

    private SupplierResponse convertToResponse(Supplier supplier) {
        SupplierResponse response = new SupplierResponse();
        response.setId(supplier.getId());
        response.setName(supplier.getName());
        response.setContactPerson(supplier.getContactPerson());
        response.setEmail(supplier.getEmail());
        response.setPhone(supplier.getPhone());
        response.setPhones(supplier.getPhones().stream()
                .map(SupplierPhone::getPhone)
                .collect(Collectors.toList()));
        response.setAddress(supplier.getAddress());
        response.setTaxId(supplier.getTaxId());
        response.setPaymentTerms(supplier.getPaymentTerms());
        response.setNotes(supplier.getNotes());
        response.setIsActive(supplier.getIsActive());
        response.setCreatedAt(supplier.getCreatedAt());
        response.setUpdatedAt(supplier.getUpdatedAt());
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

    private void fillPhones(Supplier supplier, List<String> phones) {
        if (phones.isEmpty()) {
            throw new BusinessException("At least one phone number is required");
        }
        supplier.getPhones().clear();
        for (String p : phones) {
            SupplierPhone sp = new SupplierPhone();
            sp.setSupplier(supplier);
            sp.setPhone(p);
            supplier.getPhones().add(sp);
        }
        supplier.setPhone(phones.get(0));
    }
}
