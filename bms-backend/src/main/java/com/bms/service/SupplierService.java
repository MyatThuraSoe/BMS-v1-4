package com.bms.service;

import com.bms.dto.request.SupplierCreateRequest;
import com.bms.dto.response.SupplierResponse;
import com.bms.entity.Supplier;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.SupplierRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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
        if (supplierRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("Supplier with email '" + request.getEmail() + "' already exists");
        }
        if (supplierRepository.existsByPhone(request.getPhone())) {
            throw new BusinessException("Supplier with phone '" + request.getPhone() + "' already exists");
        }

        Supplier supplier = new Supplier();
        supplier.setName(request.getName());
        supplier.setContactPerson(request.getContactPerson());
        supplier.setEmail(request.getEmail());
        supplier.setPhone(request.getPhone());
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
        if (!supplier.getEmail().equals(request.getEmail()) && supplierRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("Supplier with email '" + request.getEmail() + "' already exists");
        }
        if (!supplier.getPhone().equals(request.getPhone()) && supplierRepository.existsByPhone(request.getPhone())) {
            throw new BusinessException("Supplier with phone '" + request.getPhone() + "' already exists");
        }

        supplier.setName(request.getName());
        supplier.setContactPerson(request.getContactPerson());
        supplier.setEmail(request.getEmail());
        supplier.setPhone(request.getPhone());
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
        response.setAddress(supplier.getAddress());
        response.setTaxId(supplier.getTaxId());
        response.setPaymentTerms(supplier.getPaymentTerms());
        response.setNotes(supplier.getNotes());
        response.setIsActive(supplier.getIsActive());
        response.setCreatedAt(supplier.getCreatedAt());
        response.setUpdatedAt(supplier.getUpdatedAt());
        return response;
    }
}
