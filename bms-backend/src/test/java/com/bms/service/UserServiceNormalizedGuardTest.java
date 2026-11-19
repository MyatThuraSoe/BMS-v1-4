package com.bms.service;

import com.bms.dto.request.UserCreateRequest;
import com.bms.dto.request.UserUpdateRequest;
import com.bms.entity.Role;
import com.bms.entity.User;
import com.bms.repository.CashDrawerSessionRepository;
import com.bms.repository.CashShiftRepository;
import com.bms.repository.RoleRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N2: rows that predate {@code username_normalized} / {@code email_normalized}
 * carry nulls there. Every guard that reads them had to survive that, and the
 * duplicate checks could not rely on the normalized column alone, because an
 * old row keeps its real claim on {@code admin} while its key is still null.
 */
class UserServiceNormalizedGuardTest {

    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private AuditLogService auditLogService;
    private UserService service;

    @BeforeEach
    void buildService() {
        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        SaleRepository saleRepository = mock(SaleRepository.class);
        CashShiftRepository cashShiftRepository = mock(CashShiftRepository.class);
        CashShiftService cashShiftService = mock(CashShiftService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        auditLogService = mock(AuditLogService.class);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Role cashier = new Role();
        cashier.setId(3L);
        cashier.setName(Role.RoleName.ROLE_CASHIER);
        when(roleRepository.findById(3L)).thenReturn(Optional.of(cashier));

        service = new UserService(userRepository, roleRepository, saleRepository,
                cashShiftRepository, cashShiftService, passwordEncoder, auditLogService, mock(CashDrawerSessionRepository.class));
    }

    /** A user as it looks straight after an upgrade: no normalized keys at all. */
    private static User legacyUser() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        user.setEmail("Shop@Example.com");
        user.setPassword("hash");
        user.setFirstName("Ada");
        user.setLastName("Min");
        user.setPhone("");
        user.setIsActive(true);
        Role admin = new Role();
        admin.setId(1L);
        admin.setName(Role.RoleName.ROLE_ADMIN);
        user.setRole(admin);
        return user;
    }

    private static UserUpdateRequest updateRequest(String username, String email) {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setUsername(username);
        request.setEmail(email);
        request.setFirstName("Ada");
        request.setLastName("Min");
        request.setPhone("0900");
        request.setRoleId(1L);
        return request;
    }

    @Test
    void editingALegacyUserSucceedsAndBackfillsItsNormalizedKeys() {
        User user = legacyUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        Role admin = user.getRole();
        when(roleRepository.findById(1L)).thenReturn(Optional.of(admin));
        // Even if some row answers to the key, saving the same name is not a
        // rename onto somebody else and must not be rejected.
        when(userRepository.existsByUsernameNormalized("admin")).thenReturn(true);
        when(userRepository.existsByEmailNormalized("shop@example.com")).thenReturn(true);

        User updated = assertDoesNotThrow(() -> service.updateUser(1L, updateRequest("admin", "Shop@Example.com")));

        assertEquals("admin", updated.getUsernameNormalized(), "the edit must repair the missing key");
        assertEquals("shop@example.com", updated.getEmailNormalized());
        verify(auditLogService).logAction(eq(1L), eq("USER_UPDATE"), anyString(), eq("User"), eq(1L),
                anyString(), anyString());
    }

    @Test
    void renamingALegacyUserOntoAnotherExistingUsernameIsStillRejected() {
        User user = legacyUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(roleRepository.findById(1L)).thenReturn(Optional.of(user.getRole()));
        when(userRepository.existsOtherByUsernameIgnoreCase("Bob", 1L)).thenReturn(true);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service.updateUser(1L, updateRequest("Bob", "shop@example.com")));

        assertEquals("Username already exists", failure.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    void renamingALegacyUserOntoAnotherExistingEmailIsStillRejected() {
        User user = legacyUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(roleRepository.findById(1L)).thenReturn(Optional.of(user.getRole()));
        when(userRepository.existsOtherByEmailIgnoreCase("Taken@Example.com", 1L)).thenReturn(true);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service.updateUser(1L, updateRequest("admin", "Taken@Example.com")));

        assertEquals("Email already exists", failure.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    void creatingADuplicateOfALegacyUsernameIsRejectedEvenWithNoNormalizedKey() {
        when(userRepository.existsByUsernameNormalized("admin")).thenReturn(false);
        when(userRepository.existsByUsernameIgnoreCase("ADMIN")).thenReturn(true);

        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("ADMIN");
        request.setEmail("new@example.com");
        request.setPassword("password123");
        request.setFirstName("New");
        request.setLastName("User");
        request.setPhone("");
        request.setRoleId(3L);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service.createUser(request));

        assertEquals("Username already exists", failure.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    void creatingADuplicateOfALegacyEmailIsRejectedEvenWithNoNormalizedKey() {
        when(userRepository.existsByUsernameIgnoreCase("fresh")).thenReturn(false);
        when(userRepository.existsByEmailNormalized("shop@example.com")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("SHOP@example.com")).thenReturn(true);

        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("fresh");
        request.setEmail("SHOP@example.com");
        request.setPassword("password123");
        request.setFirstName("New");
        request.setLastName("User");
        request.setPhone("");
        request.setRoleId(3L);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service.createUser(request));

        assertEquals("Email already exists", failure.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    void aNewUserIsWrittenWithNormalizedKeysAndTokenVersionZero() {
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("  Fresh  ");
        request.setEmail(" New@Example.com ");
        request.setPassword("password123");
        request.setFirstName("New");
        request.setLastName("User");
        request.setPhone("");
        request.setRoleId(3L);

        User created = service.createUser(request);

        assertEquals("Fresh", created.getUsername(), "the display value keeps its trimming but not its case");
        assertEquals("fresh", created.getUsernameNormalized());
        assertEquals("new@example.com", created.getEmailNormalized());
        assertEquals(Integer.valueOf(0), created.getTokenVersion());
    }

    @Test
    void loginWithAnyCaseReachesTheOneAccountHoldingTheNormalizedKey() {
        User user = legacyUser();
        user.setUsernameNormalized("admin");
        when(userRepository.findByUsernameNormalized("admin")).thenReturn(Optional.of(user));

        assertEquals("admin", service.loadUserByUsername("ADMIN").getUsername());
        verify(userRepository, never()).findByUsernameIgnoreCase(anyString());
    }

    @Test
    void loginFallsBackToTheIgnoreCaseLookupForRowsNotYetBackfilled() {
        User user = legacyUser();
        when(userRepository.findByUsernameNormalized("admin")).thenReturn(Optional.empty());
        when(userRepository.findByUsernameIgnoreCase("Admin")).thenReturn(Optional.of(user));

        assertNotNull(service.loadUserByUsername("Admin"));
    }

    @Test
    void loginForAnUnknownNameStillFails() {
        when(userRepository.findByUsernameNormalized(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByUsernameIgnoreCase(anyString())).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("ghost"));
    }
}
