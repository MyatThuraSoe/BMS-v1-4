package com.bms.config;

import com.bms.dto.request.ChangePasswordRequest;
import com.bms.entity.Role;
import com.bms.entity.SystemSetting;
import com.bms.entity.User;
import com.bms.license.JwtSecretService;
import com.bms.repository.CashDrawerSessionRepository;
import com.bms.repository.CashShiftRepository;
import com.bms.repository.RoleRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SystemSettingRepository;
import com.bms.repository.UserRepository;
import com.bms.service.AuditLogService;
import com.bms.service.CashShiftService;
import com.bms.service.UserService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T6: a session is only really revoked when its token stops working. Tokens
 * minted before the version claim existed carry nothing to compare against, so
 * the shop has to be able to refuse them, and a password change must hand the
 * caller a token that survives the version bump it just caused.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenVersionRevocationTest {

    private static final String SECRET =
            Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
    private static final String USERNAME = "cashier1";

    private SystemSettingRepository systemSettingRepository;
    private JwtUtil jwtUtil;
    private Key signKey;

    @BeforeEach
    void setUp() {
        JwtSecretService secretService = mock(JwtSecretService.class);
        when(secretService.getSecret()).thenReturn(SECRET);
        systemSettingRepository = mock(SystemSettingRepository.class);
        when(systemSettingRepository.findBySettingKey(anyString())).thenReturn(Optional.empty());
        jwtUtil = new JwtUtil(secretService, systemSettingRepository);
        ReflectionTestUtils.setField(jwtUtil, "jwtExpiration", 86_400_000L);
        signKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET));
    }

    @Test
    void aTokenCarryingAnOlderVersionIsRefusedAfterTheBump() {
        User user = user(0);
        String current = jwtUtil.generateToken(user);
        assertTrue(jwtUtil.validateToken(current, user));

        user.setTokenVersion(1);
        assertFalse(jwtUtil.validateToken(current, user),
                "the token the other device is holding must stop working");
        assertTrue(jwtUtil.validateToken(jwtUtil.generateToken(user), user));
    }

    @Test
    void aLegacyTokenWithoutTheClaimStillWorksWhileTheSettingIsOff() {
        User user = user(3);
        assertTrue(jwtUtil.validateToken(legacyTokenWithoutClaim(), user));
    }

    @Test
    void turningTheSettingOnRefusesEveryClaimLessToken() {
        setting("auth.token.requireVersionClaim", "true");
        User user = user(3);

        assertFalse(jwtUtil.validateToken(legacyTokenWithoutClaim(), user));
        assertTrue(jwtUtil.validateToken(jwtUtil.generateToken(user), user));
    }

    @Test
    void changingThePasswordRevokesTheOtherDevicesAndKeepsThisOneSignedIn() {
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("rehashed");
        UserRepository userRepository = mock(UserRepository.class);
        AuditLogService auditLogService = mock(AuditLogService.class);

        User user = user(0);
        when(userRepository.findByUsernameNormalized(USERNAME)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        UserService userService = new UserService(userRepository, mock(RoleRepository.class),
                mock(SaleRepository.class), mock(CashShiftRepository.class),
                mock(CashShiftService.class), passwordEncoder, auditLogService, mock(CashDrawerSessionRepository.class));
        ReflectionTestUtils.setField(userService, "jwtUtil", jwtUtil);

        String oldToken = jwtUtil.generateToken(user);
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword("old-pass");
        request.setNewPassword("new-pass");
        String replacement = userService.changeOwnPassword(USERNAME, request);

        assertNotEquals(oldToken, replacement);
        assertEquals(Integer.valueOf(1), user.getTokenVersion());
        assertFalse(jwtUtil.validateToken(oldToken, user), "every other device is logged out");
        assertTrue(jwtUtil.validateToken(replacement, user), "this device stays signed in");
    }

    /** What a pre-T6 build issued: same subject, no version to compare against. */
    private String legacyTokenWithoutClaim() {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setSubject(USERNAME)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + 86_400_000L))
                .signWith(signKey, SignatureAlgorithm.HS256)
                .compact();
    }

    private void setting(String key, String value) {
        SystemSetting setting = new SystemSetting();
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        when(systemSettingRepository.findBySettingKey(key)).thenReturn(Optional.of(setting));
    }

    private User user(int tokenVersion) {
        Role role = new Role();
        role.setName(Role.RoleName.ROLE_CASHIER);
        User user = new User();
        user.setId(5L);
        user.setUsername(USERNAME);
        user.setPassword("stored-hash");
        user.setRole(role);
        user.setIsActive(true);
        user.setTokenVersion(tokenVersion);
        return user;
    }
}
