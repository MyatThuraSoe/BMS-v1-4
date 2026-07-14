package com.bms.service;

import com.bms.dto.request.SystemSettingRequest;
import com.bms.dto.response.SystemSettingResponse;
import com.bms.entity.SystemSetting;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.SystemSettingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class SystemSettingService {

    @Autowired
    private SystemSettingRepository systemSettingRepository;

    @Autowired
    private AuditLogService auditLogService;

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

    public SystemSettingResponse createSetting(SystemSettingRequest request, Long userId) {
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

        auditLogService.logAction(userId, "SETTING_CREATE", 
            "System setting created: " + savedSetting.getSettingKey(), 
            "SystemSetting", savedSetting.getId(), null, savedSetting.toString());

        return convertToResponse(savedSetting);
    }

    public SystemSettingResponse updateSetting(Long id, SystemSettingRequest request, Long userId) {
        SystemSetting setting = systemSettingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("System setting not found: " + id));

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

        auditLogService.logAction(userId, "SETTING_UPDATE", 
            "System setting updated: " + updatedSetting.getSettingKey(), 
            "SystemSetting", updatedSetting.getId(), oldValues, updatedSetting.toString());

        return convertToResponse(updatedSetting);
    }

    public void deleteSetting(Long id, Long userId) {
        SystemSetting setting = systemSettingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("System setting not found: " + id));

        systemSettingRepository.delete(setting);

        auditLogService.logAction(userId, "SETTING_DELETE", 
            "System setting deleted: " + setting.getSettingKey(), 
            "SystemSetting", setting.getId(), setting.toString(), null);
    }

    public SystemSettingResponse getSettingValue(String key) {
        SystemSetting setting = systemSettingRepository.findBySettingKey(key)
                .orElseThrow(() -> new ResourceNotFoundException("System setting not found: " + key));
        return convertToResponse(setting);
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
