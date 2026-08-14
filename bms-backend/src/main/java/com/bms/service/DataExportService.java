package com.bms.service;

import com.bms.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DataExportService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final SaleRepository saleRepository;
    private final PurchaseRepository purchaseRepository;
    // ⚠️ If a repository name is different in your project, adjust it here.

    /**
     * Serializes INSIDE the transaction so lazy collections load correctly.
     */
    @Transactional(readOnly = true)
    public byte[] exportAllAsJson() {
        try {
            ObjectMapper mapper = new ObjectMapper()
                    .findAndRegisterModules()   // handles LocalDateTime
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("categories", categoryRepository.findAll());
            data.put("products", productRepository.findAll());
            data.put("customers", customerRepository.findAll());
            data.put("suppliers", supplierRepository.findAll());
            data.put("sales", saleRepository.findAll());
            data.put("purchases", purchaseRepository.findAll());
            // 🔒 Users are intentionally EXCLUDED (password hashes shouldn't travel).
            //    If you want them, add: data.put("users", userRepository.findAll());

            Map<String, Object> backup = new LinkedHashMap<>();
            backup.put("app", "LumiPOS");
            backup.put("backupVersion", "1.0");
            backup.put("exportedAt", LocalDateTime.now().toString());
            backup.put("data", data);

            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(backup);
        } catch (Exception e) {
            throw new RuntimeException("Export failed: " + e.getMessage(), e);
        }
    }
}