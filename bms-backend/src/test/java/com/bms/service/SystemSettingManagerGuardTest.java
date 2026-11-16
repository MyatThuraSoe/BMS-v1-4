package com.bms.service;

import com.bms.entity.Role;
import com.bms.entity.SystemSetting;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.repository.SystemSettingRepository;
import com.bms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The controls that limit a manager (discount ceilings, cash tolerance, count
 * auto-approval, debt write-off, token enforcement) must not be editable by the
 * manager they limit.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemSettingManagerGuardTest {

    private static final long ADMIN_ID = 1L;
    private static final long MANAGER_ID = 2L;

    @Mock
    private SystemSettingRepository systemSettingRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private SystemSettingService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "shopTimeService", new ShopTimeService(null, "UTC"));
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(user(ADMIN_ID, Role.RoleName.ROLE_ADMIN)));
        when(userRepository.findById(MANAGER_ID)).thenReturn(Optional.of(user(MANAGER_ID, Role.RoleName.ROLE_MANAGER)));
        when(systemSettingRepository.save(any(SystemSetting.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private User user(Long id, Role.RoleName role) {
        Role entity = new Role();
        entity.setName(role);
        User user = new User();
        user.setId(id);
        user.setRole(entity);
        return user;
    }

    private void existing(String key, String value) {
        SystemSetting setting = new SystemSetting();
        setting.setId(7L);
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        setting.setDataType(SystemSetting.DataType.STRING);
        when(systemSettingRepository.findBySettingKey(key)).thenReturn(Optional.of(setting));
    }

    @Test
    void managerCannotRaiseTheManagerDiscountCeiling() {
        existing("shop.discount.maxPercentManager", "10.00");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.updateSettingByKey("shop.discount.maxPercentManager", "100.00", MANAGER_ID));

        assertTrue(ex.getMessage().contains("shop.discount.maxPercentManager"),
                "the refusal must name the key so the audit trail is readable");
        verify(systemSettingRepository, never()).save(any(SystemSetting.class));
    }

    @Test
    void managerCannotLoosenTheCashToleranceTheCountIsJudgedBy() {
        existing("shop.cashVarianceTolerance", "10.00");

        assertThrows(BusinessException.class, () ->
                service.updateSettingByKey("shop.cashVarianceTolerance", "9999.00", MANAGER_ID));
        verify(systemSettingRepository, never()).save(any(SystemSetting.class));
    }

    @Test
    void managerCannotChangeStockCountApprovalLimitsOrDebtWriteOffOrTokenRules() {
        for (String key : new String[]{"stockCountAutoApproveThreshold",
                "stockCountAutoApproveLineValue", "stockCountAutoApproveSheetValue",
                "stockCountAutoApproveMaxLines", "shop.belowCostPolicy",
                "shop.discount.maxPercentCashier", "shop.customer.allowWriteOff",
                "auth.token.requireVersionClaim", "shop.timezone", "invoice_prefix",
                "tax_enabled", "default_tax_rate", "currency_symbol",
                "blind_drawer_close", "allow_shared_cash_drawer",
                "require_manager_approval_shared_drawer"}) {
            existing(key, "0");

            assertThrows(BusinessException.class, () -> service.updateSettingByKey(key, "1", MANAGER_ID),
                    key + " is a control over the manager and must stay admin-only");
        }
        verify(systemSettingRepository, never()).save(any(SystemSetting.class));
    }

    @Test
    void managerStillEditsTheShopFloorKeys() {
        Map<String, String> shopFloor = new LinkedHashMap<>();
        shopFloor.put("business_name", "Mini Market");
        shopFloor.put("business_address", "12 Station Road");
        shopFloor.put("business_phone", "09-1234");
        shopFloor.put("business_email", "store@example.com");
        shopFloor.put("low_stock_threshold", "8");
        shopFloor.put("receipt_print_enabled", "true");
        shopFloor.put("shop.order.reservationHours", "48");

        shopFloor.forEach((key, value) -> {
            existing(key, "old");

            service.updateSettingByKey(key, value, MANAGER_ID);
        });

        verify(systemSettingRepository, times(shopFloor.size())).save(any(SystemSetting.class));
    }

    @Test
    void adminStillEditsAnyKey() {
        existing("shop.discount.maxPercentManager", "10.00");

        service.updateSettingByKey("shop.discount.maxPercentManager", "60.00", ADMIN_ID);

        verify(systemSettingRepository).save(any(SystemSetting.class));
    }

    @Test
    void anActorWeCannotResolveIsNotAnAdmin() {
        existing("shop.discount.maxPercentManager", "10.00");
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () ->
                service.updateSettingByKey("shop.discount.maxPercentManager", "100.00", 404L));
        verify(systemSettingRepository, never()).save(any(SystemSetting.class));
    }
}
