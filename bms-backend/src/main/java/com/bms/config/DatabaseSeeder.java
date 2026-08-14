package com.bms.config;

import com.bms.entity.InvoiceSequence;
import com.bms.entity.PurchaseSequence;
import com.bms.entity.Role;
import com.bms.repository.InvoiceSequenceRepository;
import com.bms.repository.PurchaseRepository;
import com.bms.repository.PurchaseSequenceRepository;
import com.bms.repository.RoleRepository;
import com.bms.repository.SaleRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class DatabaseSeeder implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final InvoiceSequenceRepository invoiceSequenceRepository;
    private final PurchaseSequenceRepository purchaseSequenceRepository;
    private final SaleRepository saleRepository;
    private final PurchaseRepository purchaseRepository;

    public DatabaseSeeder(RoleRepository roleRepository,
                          InvoiceSequenceRepository invoiceSequenceRepository,
                          PurchaseSequenceRepository purchaseSequenceRepository,
                          SaleRepository saleRepository,
                          PurchaseRepository purchaseRepository) {
        this.roleRepository = roleRepository;
        this.invoiceSequenceRepository = invoiceSequenceRepository;
        this.purchaseSequenceRepository = purchaseSequenceRepository;
        this.saleRepository = saleRepository;
        this.purchaseRepository = purchaseRepository;
    }

    @Override
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