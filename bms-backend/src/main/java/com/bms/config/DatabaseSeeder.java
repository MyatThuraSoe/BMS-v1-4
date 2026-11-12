package com.bms.config;

import com.bms.entity.CashDrawer;
import com.bms.entity.CashDrawerMovement;
import com.bms.entity.CashDrawerSession;
import com.bms.entity.CashShift;
import com.bms.entity.DrawerMovementType;
import com.bms.entity.DrawerSessionStatus;
import com.bms.entity.InvoiceSequence;
import com.bms.entity.PurchaseSequence;
import com.bms.entity.Role;
import com.bms.entity.SystemSetting;
import com.bms.entity.User;
import com.bms.repository.CashDrawerMovementRepository;
import com.bms.repository.CashDrawerRepository;
import com.bms.repository.CashDrawerSessionRepository;
import com.bms.repository.CashShiftRepository;
import com.bms.repository.InvoiceSequenceRepository;
import com.bms.repository.PurchaseRepository;
import com.bms.repository.PurchaseSequenceRepository;
import com.bms.repository.RoleRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SystemSettingRepository;
import com.bms.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final CashDrawerRepository cashDrawerRepository;
    private final CashDrawerSessionRepository cashDrawerSessionRepository;
    private final CashDrawerMovementRepository cashDrawerMovementRepository;
    private final CashShiftRepository cashShiftRepository;
    private final UserRepository userRepository;

    public DatabaseSeeder(RoleRepository roleRepository,
                          InvoiceSequenceRepository invoiceSequenceRepository,
                          PurchaseSequenceRepository purchaseSequenceRepository,
                          SaleRepository saleRepository,
                          PurchaseRepository purchaseRepository,
                          SystemSettingRepository systemSettingRepository,
                          CashDrawerRepository cashDrawerRepository,
                          CashDrawerSessionRepository cashDrawerSessionRepository,
                          CashDrawerMovementRepository cashDrawerMovementRepository,
                          CashShiftRepository cashShiftRepository,
                          UserRepository userRepository) {
        this.roleRepository = roleRepository;
        this.invoiceSequenceRepository = invoiceSequenceRepository;
        this.purchaseSequenceRepository = purchaseSequenceRepository;
        this.saleRepository = saleRepository;
        this.purchaseRepository = purchaseRepository;
        this.systemSettingRepository = systemSettingRepository;
        this.cashDrawerRepository = cashDrawerRepository;
        this.cashDrawerSessionRepository = cashDrawerSessionRepository;
        this.cashDrawerMovementRepository = cashDrawerMovementRepository;
        this.cashShiftRepository = cashShiftRepository;
        this.userRepository = userRepository;
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

        // Physical drawer + upgrade path for shifts opened before this feature.
        seedDefaultDrawer();
        migrateLegacyOpenShifts();
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
        defaults.put("allow_shared_cash_drawer", new String[]{"false",
                "Allow more than one employee shift to attach to one physical cash drawer", "BOOLEAN"});
        defaults.put("require_manager_approval_shared_drawer", new String[]{"false",
                "Require manager credentials when an employee joins an already-open cash drawer", "BOOLEAN"});
        defaults.put("blind_drawer_close", new String[]{"true",
                "Hide expected cash until the counted cash amount is submitted", "BOOLEAN"});

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

    private CashDrawer seedDefaultDrawer() {
        return cashDrawerRepository.findFirstByIsActiveTrueAndDeletedAtIsNullOrderByIdAsc()
                .orElseGet(() -> {
                    CashDrawer drawer = new CashDrawer();
                    drawer.setName("Main Drawer");
                    drawer.setCode("MAIN");
                    drawer.setIsActive(true);
                    return cashDrawerRepository.save(drawer);
                });
    }

    /**
     * Upgrades shifts that were open before drawer sessions existed: creates
     * (or reuses) the physical session and replays the already-recorded cash
     * sales into the movement ledger so expected cash stays correct.
     */
    private void migrateLegacyOpenShifts() {
        CashDrawer drawer = seedDefaultDrawer();
        boolean blindClose = systemSettingRepository.findBySettingKey("blind_drawer_close")
                .map(SystemSetting::getSettingValue)
                .map(value -> "true".equalsIgnoreCase(value) || "1".equals(value))
                .orElse(true);

        for (CashShift shift : cashShiftRepository.findOpenCashShiftsWithoutDrawerSession()) {
            CashDrawerSession session = cashDrawerSessionRepository
                    .findByDrawerIdAndStatus(drawer.getId(), DrawerSessionStatus.OPEN)
                    .orElse(null);
            boolean created = false;
          if (session == null) {
                session = new CashDrawerSession();
                session.setDrawer(drawer);
                session.setOpenedAt(shift.getOpeningTime() != null
                        ? shift.getOpeningTime() : LocalDateTime.now());
                User opener = userRepository.findById(shift.getCashierId()).orElse(null);
                session.setOpenedBy(opener);
                session.setOpeningFloat(shift.getOpeningAmount() != null
                        ? shift.getOpeningAmount() : BigDecimal.ZERO);
                session.setBlindClose(blindClose);
                session.setStatus(DrawerSessionStatus.OPEN);
                session = cashDrawerSessionRepository.save(session);
                created = true;

                if (session.getOpeningFloat().compareTo(BigDecimal.ZERO) != 0) {
                    recordMigrationMovement(session, DrawerMovementType.OPENING_FLOAT,
                            session.getOpeningFloat(), shift, opener, "Opening float (migrated)");
                }
            }

            shift.setDrawerSession(session);
            cashShiftRepository.save(shift);

            BigDecimal legacyCashSales = saleRepository.sumNetCashSalesByShiftId(shift.getId());
            if (legacyCashSales != null && legacyCashSales.compareTo(BigDecimal.ZERO) > 0) {
                recordMigrationMovement(session, DrawerMovementType.CASH_SALE,
                        legacyCashSales, shift, session.getOpenedBy(),
                        "Migrated cash sales for shift #" + shift.getId());
            }

            // Refunds are drawer-wide for the window, so replay them only when
            // this migration created the session.
            if (created && shift.getOpeningTime() != null) {
                BigDecimal legacyRefunds = saleRepository.sumRefundsBetween(
                        shift.getOpeningTime(), LocalDateTime.now());
                if (legacyRefunds != null && legacyRefunds.compareTo(BigDecimal.ZERO) > 0) {
                    recordMigrationMovement(session, DrawerMovementType.CASH_REFUND,
                            legacyRefunds.negate(), shift, session.getOpenedBy(),
                            "Migrated refunds before drawer sessions");
                }
            }
        }
    }

    private void recordMigrationMovement(CashDrawerSession session, DrawerMovementType type,
                                          BigDecimal amount, CashShift shift, User performedBy,
                                          String reason) {
        CashDrawerMovement movement = new CashDrawerMovement();
        movement.setDrawerSession(session);
        movement.setMovementType(type);
        movement.setAmount(amount);
        movement.setReferenceType("MIGRATION");
        movement.setReferenceId(shift.getId());
        movement.setEmployeeShift(shift);
        movement.setPerformedBy(performedBy);
        movement.setReason(reason);
        cashDrawerMovementRepository.save(movement);
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