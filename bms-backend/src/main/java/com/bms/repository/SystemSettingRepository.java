package com.bms.repository;

import com.bms.entity.SystemSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SystemSettingRepository extends JpaRepository<SystemSetting, Long> {
    Optional<SystemSetting> findBySettingKey(String settingKey);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT s FROM SystemSetting s WHERE s.settingKey = :key")
    Optional<SystemSetting> findBySettingKeyForUpdate(
            @org.springframework.data.repository.query.Param("key") String key);
    boolean existsBySettingKey(String settingKey);
}
