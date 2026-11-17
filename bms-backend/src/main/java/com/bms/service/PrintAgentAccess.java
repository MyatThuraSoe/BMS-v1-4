package com.bms.service;

import com.bms.entity.SystemSetting;
import com.bms.repository.SystemSettingRepository;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;

/**
 * The gate on the counter print queue. A print job is the shop's paper money: whoever
 * claims it decides which machine spits out the receipt, and once claimed it is off the
 * queue for good. So the queue answers to the print agent only, and what identifies the
 * agent is the machine it runs on rather than the account its window happens to be signed
 * in as — otherwise any role could poll the queue from another PC and leave the counter
 * silent. By default only a request from this server may claim or complete a job; a shop
 * that prints from a different machine turns that on with {@code print.agent.allowRemote}
 * and hands the agent the {@code print.agent.token} this install made for itself.
 */
@Component
public class PrintAgentAccess {

    public static final String ALLOW_REMOTE_KEY = "print.agent.allowRemote";
    public static final String TOKEN_KEY = "print.agent.token";
    public static final String TOKEN_HEADER = "X-Print-Agent-Token";

    private static final Logger log = LoggerFactory.getLogger(PrintAgentAccess.class);

    /** Java reports the local address either way depending on the stack and how it was reached. */
    private static final Set<String> LOOPBACK_ADDRESSES = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    private static final int TOKEN_BYTES = 32;

    private final SystemSettingRepository systemSettingRepository;
    private final SecureRandom random = new SecureRandom();

    public PrintAgentAccess(SystemSettingRepository systemSettingRepository) {
        this.systemSettingRepository = systemSettingRepository;
    }

    /** The agent token is made once per install; a shared default would be a shared secret. */
    @PostConstruct
    public void ensureAgentToken() {
        if (!value(TOKEN_KEY).isBlank()) {
            return;
        }
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);

        SystemSetting setting = systemSettingRepository.findBySettingKey(TOKEN_KEY).orElseGet(() -> {
            SystemSetting created = new SystemSetting();
            created.setSettingKey(TOKEN_KEY);
            created.setDescription("Value the counter print agent sends in the " + TOKEN_HEADER + " header");
            created.setDataType(SystemSetting.DataType.STRING);
            return created;
        });
        setting.setSettingValue(HexFormat.of().formatHex(bytes));
        systemSettingRepository.save(setting);
        log.info("Generated a counter print agent token for this install.");
    }

    public void assertMayUseQueue(HttpServletRequest request) {
        if (isLoopback(request.getRemoteAddr())) {
            return;
        }
        if (!isTrue(value(ALLOW_REMOTE_KEY)) || !tokenMatches(request.getHeader(TOKEN_HEADER))) {
            log.warn("Refused the counter print queue to {}, which is not this server", request.getRemoteAddr());
            throw new AccessDeniedException("Only the counter print agent may take print jobs");
        }
    }

    private boolean tokenMatches(String header) {
        String token = value(TOKEN_KEY);
        return !token.isBlank() && header != null
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                                         header.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isLoopback(String address) {
        return address != null && LOOPBACK_ADDRESSES.contains(address.trim());
    }

    private static boolean isTrue(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private String value(String key) {
        return systemSettingRepository.findBySettingKey(key)
                .map(SystemSetting::getSettingValue)
                .map(String::trim)
                .orElse("");
    }
}
