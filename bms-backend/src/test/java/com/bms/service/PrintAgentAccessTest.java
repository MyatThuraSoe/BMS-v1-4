package com.bms.service;

import com.bms.entity.SystemSetting;
import com.bms.repository.SystemSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Q9: taking a job off the queue is the print agent's job.
 *
 * <p>The endpoints were opened to every role because the Electron client signs in as one of
 * them, which also meant any browser on the network could empty the queue and the counter
 * would print nothing. The default chosen is the machine, not the account: only a request
 * that came from the server itself may claim work. A shop that runs its printer on another
 * PC says so in a setting and the agent carries a token this install made for it.
 */
class PrintAgentAccessTest {

    private SystemSettingRepository settings;
    private PrintAgentAccess access;

    @BeforeEach
    void buildTheGate() {
        settings = mock(SystemSettingRepository.class);
        when(settings.findBySettingKey(any())).thenReturn(Optional.empty());
        when(settings.save(any(SystemSetting.class))).thenAnswer(invocation -> invocation.getArgument(0));
        access = new PrintAgentAccess(settings);
    }

    private MockHttpServletRequest from(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/counter-print/receipt-jobs/next");
        request.setRemoteAddr(address);
        return request;
    }

    private void setting(String key, String value) {
        SystemSetting setting = new SystemSetting();
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        when(settings.findBySettingKey(key)).thenReturn(Optional.of(setting));
    }

    /** The token this install would have handed its agent, now stored where the gate reads it. */
    private String agentToken() {
        access.ensureAgentToken();
        ArgumentCaptor<SystemSetting> saved = ArgumentCaptor.forClass(SystemSetting.class);
        verify(settings, times(1)).save(saved.capture());
        String token = saved.getValue().getSettingValue();
        setting(PrintAgentAccess.TOKEN_KEY, token);
        return token;
    }

    @Test
    void theServerItselfMayClaimAndCompleteJobs() {
        access.assertMayUseQueue(from("127.0.0.1"));
        access.assertMayUseQueue(from("::1"));
        access.assertMayUseQueue(from("0:0:0:0:0:0:0:1"));
    }

    @Test
    void anotherMachineIsRefusedUntilThisShopSaysItsAgentRunsThere() {
        MockHttpServletRequest other = from("192.168.1.44");

        assertThrows(AccessDeniedException.class, () -> access.assertMayUseQueue(other));

        setting("print.agent.allowRemote", "true");
        assertThrows(AccessDeniedException.class, () -> access.assertMayUseQueue(other),
                "allowing a remote agent without giving it the token is not enough");
    }

    @Test
    void aRemoteAgentPassesOnlyWithTheTokenOfThisInstall() {
        setting("print.agent.allowRemote", "true");
        String token = agentToken();
        MockHttpServletRequest agent = from("192.168.1.44");
        agent.addHeader(PrintAgentAccess.TOKEN_HEADER, token);

        access.assertMayUseQueue(agent);

        MockHttpServletRequest eavesdropper = from("192.168.1.44");
        eavesdropper.addHeader(PrintAgentAccess.TOKEN_HEADER, token.substring(1));
        assertThrows(AccessDeniedException.class, () -> access.assertMayUseQueue(eavesdropper));
    }

    @Test
    void theTokenIsMadeOnceAndIsNotSomethingAGuesserCanArriveAt() {
        String token = agentToken();

        assertEquals(64, token.length(), "32 random bytes, hex: " + token);
        assertTrue(token.matches("[0-9a-f]{64}"), token);

        access.ensureAgentToken();
        verify(settings, times(1)).save(any(SystemSetting.class));
    }

    @Test
    void aLocalAgentIsNotAskedForATokenItWouldNotNeed() {
        agentToken();
        MockHttpServletRequest local = from("127.0.0.1");
        local.addHeader(PrintAgentAccess.TOKEN_HEADER, "wrong-on-purpose");

        access.assertMayUseQueue(local);
    }

    @Test
    void everyFormOfThisMachineIsThisMachine() {
        // A socket to 127.0.0.1 is reported as ::ffff:127.0.0.1 by some Java
        // stacks, and the whole 127.0.0.0/8 block loops back rather than only
        // .1 — a shop whose printer PC talks to 127.0.0.5 must not be told it is
        // a stranger.
        access.assertMayUseQueue(from("::ffff:127.0.0.1"));
        access.assertMayUseQueue(from("::FFFF:127.0.0.1"));
        access.assertMayUseQueue(from("127.0.0.5"));
        access.assertMayUseQueue(from("127.1.2.3"));
        access.assertMayUseQueue(from(" 127.0.0.1 "));
    }

    @Test
    void anAddressWrittenInsideIPv6IsStillJudgedOnWhatItMeans() {
        assertThrows(AccessDeniedException.class,
                () -> access.assertMayUseQueue(from("::ffff:192.168.1.20")),
                "the mapped form of a LAN address is not the server speaking");
        assertThrows(AccessDeniedException.class, () -> access.assertMayUseQueue(from(null)));
    }
}
