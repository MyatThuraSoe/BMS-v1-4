package com.bms.service;

import com.bms.entity.SystemSetting;
import com.bms.repository.SystemSettingRepository;
import com.bms.repository.UserRepository;
import com.bms.entity.Role;
import com.bms.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V5: the counter print agent's token is a door key, not a setting.
 *
 * <p>It sits in system_settings with everything else, so any manager who can open
 * the settings page could read it, and any shop that backed up its database
 * uploaded it to Google Drive. Reading it is now something only an administrator
 * may do, only by asking for it, and only with a line written in the audit log.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SecretSettingVisibilityTest {

    private static final String TOKEN_KEY = "print.agent.token";
    private static final String TOKEN_VALUE = "6c9a1e7b4d2f80a5c3e1b9d7f5a3c1e9";
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
    void storeASecretAndAnOrdinarySetting() {
        ReflectionTestUtils.setField(service, "shopTimeService", new ShopTimeService(null, "UTC"));
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(user(ADMIN_ID, Role.RoleName.ROLE_ADMIN)));
        when(userRepository.findById(MANAGER_ID)).thenReturn(Optional.of(user(MANAGER_ID, Role.RoleName.ROLE_MANAGER)));

        SystemSetting token = setting(7L, TOKEN_KEY, TOKEN_VALUE);
        SystemSetting name = setting(8L, "business_name", "LumiPOS Corner Shop");
        when(systemSettingRepository.findBySettingKey(TOKEN_KEY)).thenReturn(Optional.of(token));
        when(systemSettingRepository.findBySettingKey("business_name")).thenReturn(Optional.of(name));
        when(systemSettingRepository.findAll()).thenReturn(List.of(token, name));
    }

    private User user(Long id, Role.RoleName role) {
        Role entity = new Role();
        entity.setName(role);
        User user = new User();
        user.setId(id);
        user.setRole(entity);
        return user;
    }

    private SystemSetting setting(Long id, String key, String value) {
        SystemSetting setting = new SystemSetting();
        setting.setId(id);
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        setting.setDataType(SystemSetting.DataType.STRING);
        return setting;
    }

    @Test
    void aManagerReadingTheTokenSeesDots() {
        assertEquals(SystemSettingService.MASKED_VALUE,
                service.getSettingByKey(TOKEN_KEY).getSettingValue());
    }

    @Test
    void anOrdinarySettingIsStillItself() {
        assertEquals("LumiPOS Corner Shop", service.getSettingByKey("business_name").getSettingValue());
    }

    @Test
    void theWholeListMasksTheTokenAndKeepsTheRest() {
        List<com.bms.dto.response.SystemSettingResponse> all = service.getAllSettings();

        assertEquals(SystemSettingService.MASKED_VALUE,
                all.stream().filter(s -> TOKEN_KEY.equals(s.getSettingKey())).findFirst().orElseThrow()
                        .getSettingValue());
        assertEquals("LumiPOS Corner Shop",
                all.stream().filter(s -> "business_name".equals(s.getSettingKey())).findFirst().orElseThrow()
                        .getSettingValue());
    }

    @Test
    void anAdministratorWhoDoesNotAskSeesDotsToo() {
        // The list endpoint is administrator-only already, and still no secret
        // comes off it unless somebody asks for it by name.
        assertEquals(SystemSettingService.MASKED_VALUE,
                service.getSettingByKey(TOKEN_KEY).getSettingValue());
        verify(auditLogService, never()).logAction(any(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void anAdministratorWhoRevealsGetsTheTokenAndIsWrittenDown() {
        assertEquals(TOKEN_VALUE,
                service.getSettingByKey(TOKEN_KEY, true, ADMIN_ID).getSettingValue());

        verify(auditLogService).logAction(eq(ADMIN_ID), eq("SETTING_REVEALED"), contains(TOKEN_KEY),
                eq("SystemSetting"), eq(7L), isNull(), isNull());
    }

    @Test
    void theRevealedListCarriesTheTokenAndIsWrittenDownToo() {
        List<com.bms.dto.response.SystemSettingResponse> all = service.getAllSettings(true, ADMIN_ID);

        assertEquals(TOKEN_VALUE,
                all.stream().filter(s -> TOKEN_KEY.equals(s.getSettingKey())).findFirst().orElseThrow()
                        .getSettingValue());
        verify(auditLogService).logAction(eq(ADMIN_ID), eq("SETTING_REVEALED"), contains(TOKEN_KEY),
                eq("SystemSetting"), eq(7L), isNull(), isNull());
    }

    @Test
    void aManagerCannotRevealEvenByAsking() {
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.getSettingByKey(TOKEN_KEY, true, MANAGER_ID));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.getAllSettings(true, MANAGER_ID));
        verify(auditLogService, never()).logAction(any(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void revealingAnOrdinarySettingIsNotAnEventWorthRecording() {
        assertEquals("LumiPOS Corner Shop",
                service.getSettingByKey("business_name", true, MANAGER_ID).getSettingValue());

        verify(auditLogService, never()).logAction(any(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void askingWithoutRevealingAsksNothingMoreOfTheService() {
        assertEquals(SystemSettingService.MASKED_VALUE,
                service.getSettingByKey(TOKEN_KEY, false, ADMIN_ID).getSettingValue());
        assertEquals(SystemSettingService.MASKED_VALUE,
                service.getAllSettings(false, ADMIN_ID).stream()
                        .filter(s -> TOKEN_KEY.equals(s.getSettingKey())).findFirst().orElseThrow()
                        .getSettingValue());
        verify(auditLogService, never()).logAction(any(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void theSecretKeyListIsTheOnePlaceThatKnows() {
        // PrintAgentAccess is where the token is made; this is where it is hidden.
        // The two have to agree, or the masking quietly protects nothing.
        assertTrue(SystemSettingService.SECRET_KEYS.contains(PrintAgentAccess.TOKEN_KEY));
    }
}
