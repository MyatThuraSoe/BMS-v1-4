package com.bms.service;

import com.bms.entity.SystemSetting;
import com.bms.repository.SystemSettingRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The number this install gave itself the first time it started.
 *
 * <p>A merge asks whether the rows in a file are rows this shop already has, and
 * the only honest answer comes from knowing which shop took the file. Ids are
 * handed out by whichever database wrote them, so two tills can easily both hold a
 * sale with id 17 that means something else entirely — and merging one into the
 * other splices two histories together while reporting success. {@code install.id}
 * is what the file says about itself, and what a merge is allowed to trust.
 *
 * <p>It is a random UUID, made once and never changed: not a secret (it goes into
 * every export) and not something a shop should be able to edit. It is stored as a
 * system setting so an ordinary database backup carries it along with the rest of
 * the shop.
 */
@Component
public class InstallIdentity {

    public static final String SETTING_KEY = "install.id";

    private static final Logger log = LoggerFactory.getLogger(InstallIdentity.class);

    private final SystemSettingRepository systemSettingRepository;

    /**
     * What this process started with. A file exported between two writes must not
     * suddenly name a different install, so the value outlives the row it came
     * from: an export runs in a read-only transaction and cannot put the row back
     * if someone deleted it.
     */
    private volatile String startedWith;

    public InstallIdentity(SystemSettingRepository systemSettingRepository) {
        this.systemSettingRepository = systemSettingRepository;
    }

    @PostConstruct
    public void ensureInstallId() {
        String stored = storedId();
        if (stored.isBlank()) {
            stored = newId();
            save(stored);
            log.info("This install is {} and every export now says so.", stored);
        }
        startedWith = stored;
    }

    /** The id of this shop, as it is written in its database. Never blank. */
    public String currentId() {
        String stored = storedId();
        if (!stored.isBlank()) {
            if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                startedWith = stored;
            }
            return stored;
        }
        if (startedWith != null) {
            return startedWith;
        }
        String fresh = newId();
        save(fresh);
        startedWith = fresh;
        return fresh;
    }

    /**
     * Takes over the identity of a file that replaced this shop completely.
     *
     * <p>After Replace everything, the rows here are the rows that were there, so
     * the id has to be the one the file carried: backups of the same shop taken
     * before and after the move then merge instead of looking like two different
     * businesses.
     */
    public void adopt(String fileId) {
        String target = fileId == null || fileId.isBlank() ? currentId() : fileId;
        if (!target.equals(storedId())) save(target);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            startedWith = target;
                        }
                    });
        } else {
            startedWith = target;
        }
    }

    private String storedId() {
        return systemSettingRepository.findBySettingKey(SETTING_KEY)
                .map(SystemSetting::getSettingValue)
                .orElse("")
                .trim();
    }

    private void save(String value) {
        SystemSetting setting = systemSettingRepository.findBySettingKey(SETTING_KEY).orElseGet(() -> {
            SystemSetting created = new SystemSetting();
            created.setSettingKey(SETTING_KEY);
            created.setDescription("Identifies this install, so a merge knows whose file it is");
            created.setDataType(SystemSetting.DataType.STRING);
            return created;
        });
        setting.setSettingValue(value);
        systemSettingRepository.save(setting);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
