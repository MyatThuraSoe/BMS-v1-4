package com.bms.controller;

import com.bms.dto.response.ApiResponse;
import com.bms.service.DataExportService;
import com.bms.service.DataImportService;
import com.bms.service.InstallIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The import page can read the install id out of a backup file, but it cannot
 * know this computer's own unless the backend says it. Without that it has no
 * way to tell a file made here from a stranger's file before the merge starts,
 * so it would have to warn about every file or none.
 */
class InstallIdEndpointTest {

    @Test
    @SuppressWarnings("unchecked")
    void theImportPageCanAskWhichInstallThisIs() {
        InstallIdentity install = mock(InstallIdentity.class);
        when(install.currentId()).thenReturn("6f2a1c00-0000-0000-0000-000000000001");
        DataController controller = new DataController(
                mock(DataExportService.class), mock(DataImportService.class), install);

        ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.installId();

        assertEquals("6f2a1c00-0000-0000-0000-000000000001",
                ((Map<String, Object>) response.getBody().getData()).get("installId"),
                "the field name is the one the export writes into the file, so the page can compare them");
    }
}
