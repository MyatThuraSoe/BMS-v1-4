package com.bms.config;

import com.bms.entity.InvoiceSequence;
import com.bms.entity.PurchaseSequence;
import com.bms.entity.Role;
import com.bms.entity.SystemSetting;
import com.bms.repository.InvoiceSequenceRepository;
import com.bms.repository.PurchaseRepository;
import com.bms.repository.PurchaseSequenceRepository;
import com.bms.repository.RoleRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SystemSettingRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DatabaseSeeder implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final InvoiceSequenceRepository invoiceSequenceRepository;
    private final PurchaseSequenceRepository purchaseSequenceRepository;
    private final SaleRepository saleRepository;
    private final PurchaseRepository purchaseRepository;
    private final SystemSettingRepository systemSettingRepository;

    public DatabaseSeeder(RoleRepository roleRepository,
                          InvoiceSequenceRepository invoiceSequenceRepository,
                          PurchaseSequenceRepository purchaseSequenceRepository,
                          SaleRepository saleRepository,
                          PurchaseRepository purchaseRepository,
                          SystemSettingRepository systemSettingRepository) {
        this.roleRepository = roleRepository;
        this.invoiceSequenceRepository = invoiceSequenceRepository;
        this.purchaseSequenceRepository = purchaseSequenceRepository;
        this.saleRepository = saleRepository;
        this.purchaseRepository = purchaseRepository;
        this.systemSettingRepository = systemSettingRepository;
    }

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        // Loop through all the roles in your Enum and create them if they are missing
        for (Role.RoleName roleName : Role.RoleName.values()) {
            if (roleRepository.findByName(roleName).isEmpty()) {
                Role newRole = new Role();
                newRole.setName(roleName);
                roleRepository.save(newRole);
            }
        }

        // Seed today's number sequence rows so the first invoice/PO of the day
        // takes a PESSIMISTIC_WRITE lock instead of a racy insert.
        seedInvoiceSequence(LocalDate.now());
        seedPurchaseSequence(LocalDate.now());

        // Seed default system settings if missing
        seedSystemSettings();
    }

    private void seedSystemSettings() {
        Map<String, String[]> defaults = new LinkedHashMap<>();
        defaults.put("business_name", new String[]{"My Business", "Name of the business", "STRING"});
        defaults.put("business_address", new String[]{"", "Address of the business", "STRING"});
        defaults.put("business_phone", new String[]{"", "Phone number of the business", "STRING"});
        defaults.put("business_email", new String[]{"", "Email of the business", "STRING"});
        defaults.put("currency_symbol", new String[]{"$", "Currency symbol used in the system", "STRING"});
        defaults.put("tax_enabled", new String[]{"false", "Whether tax calculation is enabled", "BOOLEAN"});
        defaults.put("default_tax_rate", new String[]{"0.00", "Default tax rate applied to sales", "DECIMAL"});
        defaults.put("low_stock_threshold", new String[]{"5", "Default threshold for low stock alerts", "INTEGER"});
        defaults.put("receipt_print_enabled", new String[]{"true", "Whether receipt printing is enabled", "BOOLEAN"});
        defaults.put("invoice_prefix", new String[]{"INV-", "Prefix for invoice numbers", "STRING"});

        for (Map.Entry<String, String[]> entry : defaults.entrySet()) {
            if (systemSettingRepository.findBySettingKey(entry.getKey()).isEmpty()) {
                SystemSetting setting = new SystemSetting();
                setting.setSettingKey(entry.getKey());
                setting.setSettingValue(entry.getValue()[0]);
                setting.setDescription(entry.getValue()[1]);
                setting.setDataType(SystemSetting.DataType.valueOf(entry.getValue()[2]));
                systemSettingRepository.save(setting);
            }
        }
    }

    private void seedInvoiceSequence(LocalDate today) {
        if (invoiceSequenceRepository.findByDateForUpdate(today).isEmpty()) {
            String prefix = "INV" + today.format(DateTimeFormatter.ofPattern("yyMMdd"));
            List<String> numbers = saleRepository.findInvoiceNumbersByPrefix(prefix, PageRequest.of(0, 1));
            int legacy = 0;
            if (!numbers.isEmpty()) {
                legacy = Integer.parseInt(numbers.get(0).substring(prefix.length()));
            }
            InvoiceSequence seq = new InvoiceSequence();
            seq.setSequenceDate(today);
            seq.setLastNumber(legacy);
            invoiceSequenceRepository.save(seq);
        }
    }

    private void seedPurchaseSequence(LocalDate today) {
        if (purchaseSequenceRepository.findByDateForUpdate(today).isEmpty()) {
            String prefix = "PO-" + today.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
            List<String> numbers = purchaseRepository.findPurchaseNumbersByPrefix(prefix, PageRequest.of(0, 1));
            int legacy = 0;
            if (!numbers.isEmpty()) {
                legacy = Integer.parseInt(numbers.get(0).substring(prefix.length()));
            }
            PurchaseSequence seq = new PurchaseSequence();
            seq.setSequenceDate(today);
            seq.setLastNumber(legacy);
            purchaseSequenceRepository.save(seq);
        }
    }
}