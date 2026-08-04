package com.bms.repository;

import com.bms.entity.BackupSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface BackupSettingRepository extends JpaRepository<BackupSetting, Long> {
    Optional<BackupSetting> findFirstByOrderByIdAsc();
}