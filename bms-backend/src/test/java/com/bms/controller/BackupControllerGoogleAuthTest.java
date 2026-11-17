package com.bms.controller;

import com.bms.repository.BackupSettingRepository;
import com.bms.service.GoogleDriveService;
import com.bms.service.ShopTimeService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * The Google sign-in is an option, not a requirement: a shop that never connects Drive
 * sets none of its properties, and the app must still start and still refuse to pile up
 * the state tokens of sign-ins nobody finished.
 */
class BackupControllerGoogleAuthTest {

    /** The two beans that read the Google properties, with no property source at all. */
    static class NoGoogleProperties {

        @Bean
        PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        BackupSettingRepository backupSettingRepository() {
            return mock(BackupSettingRepository.class);
        }

        @Bean
        ShopTimeService shopTimeService() {
            return mock(ShopTimeService.class);
        }

        @Bean
        GoogleDriveService googleDriveService(BackupSettingRepository repository) {
            return new GoogleDriveService(repository);
        }

        @Bean
        BackupController backupController(BackupSettingRepository repository, GoogleDriveService drive,
                                          ShopTimeService shopTime) {
            return new BackupController(repository, null, drive, null, shopTime);
        }
    }

    @Test
    void theAppStartsWhenNoGooglePropertyIsSet() {
        // Refreshing is the assertion: an unresolved placeholder would throw here.
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(NoGoogleProperties.class)) {
            BackupController controller = context.getBean(BackupController.class);
            assertEquals("", field(controller, "clientId"),
                    "no client id means no Drive connection, not a shop that cannot start");
            assertEquals("http://127.0.0.1:17234/api/backups/google/callback", field(controller, "redirectUri"),
                    "the callback is the address the app itself listens on");
            assertEquals("http://127.0.0.1:17234", field(controller, "frontendRedirectBase"),
                    "the browser is sent back to the address the app is served from");
            assertEquals("", field(context.getBean(GoogleDriveService.class), "clientId"));
        }
    }

    @Test
    void anAuthLinkNobodyFinishedWithIsForgottenWhenTheNextOneIsMade() {
        BackupController controller = new BackupController(null, null, null, null, null);
        ReflectionTestUtils.setField(controller, "clientId", "test-client");
        ReflectionTestUtils.setField(controller, "redirectUri",
                "http://127.0.0.1:17234/api/backups/google/callback");
        MockHttpServletRequest request = new MockHttpServletRequest();

        for (int i = 0; i < 10; i++) {
            controller.getAuthUrl(request);
        }
        Map<String, Instant> pending = pendingStates(controller);
        assertEquals(10, pending.size(), "every link opened is one state waiting for a callback");

        // The minutes go by and the admin closes the browser without signing in.
        pending.replaceAll((state, expiresAt) -> Instant.now().minusSeconds(1));

        controller.getAuthUrl(request);

        assertEquals(1, pending.size(),
                "the link just made is the only state left; a used-up one is dead weight: " + pending);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Instant> pendingStates(BackupController controller) {
        return (Map<String, Instant>) ReflectionTestUtils.getField(controller, "pendingOAuthStates");
    }

    private static Object field(Object target, String name) {
        return ReflectionTestUtils.getField(target, name);
    }
}
