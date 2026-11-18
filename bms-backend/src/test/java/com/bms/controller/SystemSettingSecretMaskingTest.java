package com.bms.controller;

import com.bms.dto.response.ApiResponse;
import com.bms.dto.response.SystemSettingResponse;
import com.bms.service.SystemSettingService;
import com.bms.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which half of a settings read is the page's doing and which is the shop's.
 *
 * <p>The masked dots come back for every ordinary read; only a request that says
 * "reveal", made by somebody holding the administrator seat, gets the stored text
 * and a line in the audit log.
 */
class SystemSettingSecretMaskingTest {

    private static final String TOKEN_KEY = "print.agent.token";
    private static final String TOKEN = "6d1f0c4be9a75833c1d0e6a4b90f2255";
    private static final long ADMIN_USER_ID = 11L;

    private SystemSettingService service;
    private SystemSettingController controller;

    @BeforeEach
    void wireTheSettingsApi() {
        service = mock(SystemSettingService.class);
        UserService users = mock(UserService.class);
        com.bms.entity.User admin = new com.bms.entity.User();
        admin.setId(ADMIN_USER_ID);
        admin.setUsername("admin");
        when(users.findByUsername("admin")).thenReturn(admin);

        controller = new SystemSettingController();
        ReflectionTestUtils.setField(controller, "systemSettingService", service);
        ReflectionTestUtils.setField(controller, "userService", users);
    }

    private static Authentication as(String username, String role) {
        return new UsernamePasswordAuthenticationToken(username, "n/a",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(role)));
    }

    private static SystemSettingResponse storedToken() {
        SystemSettingResponse response = new SystemSettingResponse();
        response.setId(4L);
        response.setSettingKey(TOKEN_KEY);
        return response;
    }

    @Test
    void anOrdinaryReadKeepsTheDotsTheServicePutThere() {
        SystemSettingResponse masked = storedToken();
        masked.setSettingValue(SystemSettingService.MASKED_VALUE);
        when(service.getSettingByKey(TOKEN_KEY)).thenReturn(masked);

        ResponseEntity<ApiResponse<SystemSettingResponse>> response =
                controller.getSettingByKey(TOKEN_KEY, false, as("manager", "ROLE_MANAGER"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(SystemSettingService.MASKED_VALUE, response.getBody().getData().getSettingValue());
        verify(service, never()).getSettingByKey(TOKEN_KEY, true, ADMIN_USER_ID);
    }

    @Test
    void anAdministratorAskingForTheRealTextGetsIt() {
        SystemSettingResponse revealed = storedToken();
        revealed.setSettingValue(TOKEN);
        when(service.getSettingByKey(TOKEN_KEY, true, ADMIN_USER_ID)).thenReturn(revealed);

        ResponseEntity<ApiResponse<SystemSettingResponse>> response =
                controller.getSettingByKey(TOKEN_KEY, true, as("admin", "ROLE_ADMIN"));

        assertEquals(TOKEN, response.getBody().getData().getSettingValue());
        verify(service).getSettingByKey(TOKEN_KEY, true, ADMIN_USER_ID);
    }

    @Test
    void aManagerAskingForTheRealTextIsRefusedAndNotServedTheDotsInstead() {
        ResponseEntity<ApiResponse<SystemSettingResponse>> response =
                controller.getSettingByKey(TOKEN_KEY, true, as("mallory", "ROLE_MANAGER"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "silently answering a reveal with a mask teaches the page that reveal does nothing");
        verify(service, never()).getSettingByKey(anyString(), org.mockito.ArgumentMatchers.eq(true), anyLong());
    }

    @Test
    void theListIsMaskedAndRevealedOnlyOnAnAdministratorsAsking() {
        SystemSettingResponse masked = storedToken();
        masked.setSettingValue(SystemSettingService.MASKED_VALUE);
        when(service.getAllSettings()).thenReturn(List.of(masked));
        SystemSettingResponse revealed = storedToken();
        revealed.setSettingValue(TOKEN);
        when(service.getAllSettings(true, ADMIN_USER_ID)).thenReturn(List.of(revealed));

        assertEquals(SystemSettingService.MASKED_VALUE,
                controller.getAllSettings(false, as("admin", "ROLE_ADMIN")).getBody().getData()
                        .get(0).getSettingValue());
        assertEquals(TOKEN,
                controller.getAllSettings(true, as("admin", "ROLE_ADMIN")).getBody().getData()
                        .get(0).getSettingValue());
        verify(service, never()).getAllSettings(false, ADMIN_USER_ID);
    }
}
