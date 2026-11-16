package com.bms.service;

import com.bms.dto.request.SystemSettingRequest;
import com.bms.dto.response.SystemSettingResponse;
import com.bms.entity.Role;
import com.bms.entity.SystemSetting;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.SystemSettingRepository;
import com.bms.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.math.BigDecimal;
import java.util.stream.Collectors;

@Service
@Transactional
public class SystemSettingService {

    private static final Logger log = LoggerFactory.getLogger(SystemSettingService.class);

    /**
     * Keys a manager may change. Everything else under {@code /api/settings/key}
     * is administrator-only, because the ceilings a manager works inside (discount
     * limits, cash variance, count auto-approval, below-cost policy, debt write-off,
     * token enforcement, tax and numbering) must not be editable from that seat.
     */
    private static final Set<String> MANAGER_EDITABLE_KEYS = Set.of(
            "business_name",
            "business_address",
            "business_phone",
            "business_email",
            "low_stock_threshold",
            "receipt_print_enabled",
            "shop.order.reservationHours");

    @Autowired
    private ShopTimeService shopTimeService;

    @Autowired
    private SystemSettingRepository systemSettingRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private UserRepository userRepository;

    private volatile BigDecimal cachedCashVarianceTolerance;
    private volatile long cashVarianceToleranceCachedAt;

    public BigDecimal cashVarianceTolerance() {
        long now = System.currentTimeMillis();
        BigDecimal cached = cachedCashVarianceTolerance;
        if (cached != null && now - cashVarianceToleranceCachedAt < 60_000L) {
            return cached;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (cachedCashVarianceTolerance == null || now - cashVarianceToleranceCachedAt >= 60_000L) {
                cachedCashVarianceTolerance = readCashVarianceTolerance();
                cashVarianceToleranceCachedAt = now;
            }
            return cachedCashVarianceTolerance;
        }
    }

    /** S2: a hand-edited row must not take cash shift closing down with it. */
    private BigDecimal readCashVarianceTolerance() {
        BigDecimal defaultValue = new BigDecimal("10.00");
        String raw = systemSettingRepository.findBySettingKey("shop.cashVarianceTolerance")
                .map(SystemSetting::getSettingValue)
                .filter(value -> value != null && !value.isBlank())
                .orElse(null);
        if (raw == null) {
            return defaultValue;
        }
        try {
            BigDecimal value = new BigDecimal(raw);
            if (value.signum() < 0) {
                throw new NumberFormatException("negative");
            }
            return value;
        } catch (NumberFormatException ex) {
            log.warn("Setting shop.cashVarianceTolerance holds \"{}\" which is not an amount; using {}",
                    raw, defaultValue);
            return defaultValue;
        }
    }

    @Cacheable(cacheNames = "systemSettings")
    public List<SystemSettingResponse> getAllSettings() {
        return systemSettingRepository.findAll().stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    public SystemSettingResponse getSettingById(Long id) {
        SystemSetting setting = systemSettingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("System setting not found: " + id));
        return convertToResponse(setting);
    }

    public SystemSettingResponse getSettingByKey(String key) {
        SystemSetting setting = systemSettingRepository.findBySettingKey(key)
                .orElseThrow(() -> new ResourceNotFoundException("System setting not found: " + key));
        return convertToResponse(setting);
    }

    @CacheEvict(cacheNames = "systemSettings", allEntries = true)
    public SystemSettingResponse createSetting(SystemSettingRequest request, Long userId) {
        assertEditable(request.getSettingKey());
        validateValue(request.getSettingKey(), request.getSettingValue());
        if (systemSettingRepository.existsBySettingKey(request.getSettingKey())) {
            throw new BusinessException("Setting with key '" + request.getSettingKey() + "' already exists");
        }

        SystemSetting setting = new SystemSetting();
        setting.setSettingKey(request.getSettingKey());
        setting.setSettingValue(request.getSettingValue());
        setting.setDescription(request.getDescription());
        setting.setDataType(SystemSetting.DataType.valueOf(request.getDataType()));
        setting.setIsPublic(request.getIsPublic() != null ? request.getIsPublic() : false);

        SystemSetting savedSetting = systemSettingRepository.save(setting);
        invalidateToleranceCache();

        auditLogService.logAction(userId, "SETTING_CREATE", 
            "System setting created: " + savedSetting.getSettingKey(), 
            "SystemSetting", savedSetting.getId(), null, savedSetting.toString());

        return convertToResponse(savedSetting);
    }

    @CacheEvict(cacheNames = "systemSettings", allEntries = true)
    public SystemSettingResponse updateSetting(Long id, SystemSettingRequest request, Long userId) {
        assertEditable(request.getSettingKey());
        validateValue(request.getSettingKey(), request.getSettingValue());
        SystemSetting setting = systemSettingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("System setting not found: " + id));

        assertEditable(setting.getSettingKey());
        String oldValues = setting.toString();

        if (!setting.getSettingKey().equals(request.getSettingKey()) && 
            systemSettingRepository.existsBySettingKey(request.getSettingKey())) {
            throw new BusinessException("Setting with key '" + request.getSettingKey() + "' already exists");
        }

        setting.setSettingKey(request.getSettingKey());
        setting.setSettingValue(request.getSettingValue());
        setting.setDescription(request.getDescription());
        setting.setDataType(SystemSetting.DataType.valueOf(request.getDataType()));
        setting.setIsPublic(request.getIsPublic() != null ? request.getIsPublic() : false);

        SystemSetting updatedSetting = systemSettingRepository.save(setting);
        invalidateToleranceCache();

        auditLogService.logAction(userId, "SETTING_UPDATE", 
            "System setting updated: " + updatedSetting.getSettingKey(), 
            "SystemSetting", updatedSetting.getId(), oldValues, updatedSetting.toString());

        return convertToResponse(updatedSetting);
    }

    @CacheEvict(cacheNames = "systemSettings", allEntries = true)
    public SystemSettingResponse updateSettingByKey(String key, String settingValue, Long userId) {
        assertKeyEditableBy(key, userId);
        validateValue(key, settingValue);
        SystemSetting setting = systemSettingRepository.findBySettingKey(key)
                .orElseThrow(() -> new ResourceNotFoundException("System setting not found: " + key));

        assertEditable(setting.getSettingKey());
        String oldValues = setting.toString();

        setting.setSettingValue(settingValue);

        SystemSetting updatedSetting = systemSettingRepository.save(setting);
        invalidateToleranceCache();

        auditLogService.logAction(userId, "SETTING_UPDATE",
            "System setting updated: " + updatedSetting.getSettingKey(),
            "SystemSetting", updatedSetting.getId(), oldValues, updatedSetting.toString());

        return convertToResponse(updatedSetting);
    }

    @CacheEvict(cacheNames = "systemSettings", allEntries = true)
    public void deleteSetting(Long id, Long userId) {
        SystemSetting setting = systemSettingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("System setting not found: " + id));

        assertEditable(setting.getSettingKey());
        systemSettingRepository.delete(setting);
        invalidateToleranceCache();

        auditLogService.logAction(userId, "SETTING_DELETE", 
            "System setting deleted: " + setting.getSettingKey(), 
            "SystemSetting", setting.getId(), setting.toString(), null);
    }

    public SystemSettingResponse getSettingValue(String key) {
        SystemSetting setting = systemSettingRepository.findBySettingKey(key)
                .orElseThrow(() -> new ResourceNotFoundException("System setting not found: " + key));
        return convertToResponse(setting);
    }

    private void assertEditable(String key) {
        if (key != null && key.startsWith("accounting.")) {
            throw new BusinessException("Internal accounting settings cannot be edited through this endpoint");
        }
    }

    private void assertKeyEditableBy(String key, Long userId) {
        if (isAdmin(userId) || MANAGER_EDITABLE_KEYS.contains(key)) {
            return;
        }
        log.warn("User {} tried to change administrator-only setting '{}'", userId, key);
        throw new BusinessException("Only an administrator may change '" + key + "'");
    }

    private boolean isAdmin(Long userId) {
        return userRepository.findById(userId)
                .map(User::getRole)
                .map(Role::getName)
                .filter(Role.RoleName.ROLE_ADMIN::equals)
                .isPresent();
    }

    private void validateValue(String key, String value) {
        if (key == null || value == null) return;
        if ("shop.cashVarianceTolerance".equals(key)
                || "stockCountAutoApproveLineValue".equals(key)
                || "stockCountAutoApproveSheetValue".equals(key)
                || "stockCountAutoApproveThreshold".equals(key)
                || "shop.discount.maxPercentCashier".equals(key)
                || "shop.discount.maxPercentManager".equals(key)
                || "shop.order.reservationHours".equals(key)) {
            try {
                BigDecimal decimal = new BigDecimal(value);
                if ("shop.order.reservationHours".equals(key)) {
                    int hours = Integer.parseInt(value);
                    if (hours < 0) {
                        throw new BusinessException("Reservation hours cannot be negative");
                    }
                } else if (decimal.signum() < 0) {
                    throw new BusinessException("Setting value cannot be negative");
                }
            } catch (NumberFormatException ex) {
                if ("shop.order.reservationHours".equals(key)) {
                    throw new BusinessException("Reservation hours must be a whole number");
                }
                throw new BusinessException("Setting value must be a valid amount");
            }
        }
        if ("stockCountAutoApproveMaxLines".equals(key)) {
            try {
                if (Integer.parseInt(value) < 0) {
                    throw new BusinessException("Maximum count lines cannot be negative");
                }
            } catch (NumberFormatException ex) {
                throw new BusinessException("Maximum count lines must be a whole number");
            }
        }
    }

    private void invalidateToleranceCache() {
        cachedCashVarianceTolerance = null;
        cashVarianceToleranceCachedAt = 0;
    }

    private SystemSettingResponse convertToResponse(SystemSetting setting) {
        SystemSettingResponse response = new SystemSettingResponse();
        response.setId(setting.getId());
        response.setSettingKey(setting.getSettingKey());
        response.setSettingValue(setting.getSettingValue());
        response.setDescription(setting.getDescription());
        response.setDataType(setting.getDataType().name());
        response.setIsPublic(setting.getIsPublic());
        response.setCreatedAt(setting.getCreatedAt());
        response.setUpdatedAt(setting.getUpdatedAt());
        return response;
    }
}
