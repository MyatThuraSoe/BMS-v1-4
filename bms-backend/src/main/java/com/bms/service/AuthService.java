package com.bms.service;

import com.bms.dto.request.LoginRequest;
import com.bms.dto.request.RegisterRequest;
import com.bms.dto.response.LoginResponse;
import com.bms.dto.response.UserResponse;
import com.bms.entity.Role;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.repository.RoleRepository;
import com.bms.repository.UserRepository;
import com.bms.config.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;


@Service
public class AuthService {

    @Autowired
    private ShopTimeService shopTimeService;

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    @Autowired
    private LoginAttemptTracker attemptTracker;

    @Autowired
    private LoginAttemptPolicy loginPolicy;

    private final Object firstAdminLock = new Object();

    private String attemptKey(String username, String clientAddress) {
        return LoginAttemptTracker.loginKey(username, clientAddress);
    }


    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private AuditLogService auditLogService;

    public LoginResponse authenticateUser(LoginRequest loginRequest) {
        return authenticateUser(loginRequest, null);
    }

    /**
     * @param clientAddress where the attempt came from, resolved by
     *                      {@link LoginAttemptPolicy}. {@code null} when no HTTP request is
     *                      behind the call: the username is then counted against a key of
     *                      its own and no address is counted at all.
     */
    public LoginResponse authenticateUser(LoginRequest loginRequest, String clientAddress) {
        String key = attemptKey(loginRequest.getUsername(), clientAddress);
        String addressKey = LoginAttemptTracker.addressKey(clientAddress);

        // The address is asked first. Its refusal is about the machine, and answering it
        // with the account's own lock would confirm that the username is real.
        if (attemptTracker.isLocked(addressKey)) {
            log.warn("Login refused from a locked address: {}", clientAddress);
            throw new BusinessException("auth.login.failed");
        }

        if (attemptTracker.isLocked(key)) {
            log.warn("Login rejected for locked account: username={} from {}", key, clientAddress);
            throw new BusinessException("auth.login.locked");
        }

        try {
            log.debug("Attempting login for username={}", loginRequest.getUsername());

            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginRequest.getUsername(),
                            loginRequest.getPassword()
                    )
            );

            SecurityContextHolder.getContext().setAuthentication(authentication);

            Object principal = authentication.getPrincipal();
            if (!(principal instanceof User user)) {
                log.error("Unexpected authentication principal type: {}", principal == null ? "null" : principal.getClass().getName());
                throw new BusinessException("auth.login.failed");
            }

            // Credentials were correct, but the admin deactivated this account.
            // Blocked here so no JWT is ever issued; no lockout penalty applies.
            if (!Boolean.TRUE.equals(user.getIsActive())) {
                SecurityContextHolder.clearContext();
                attemptTracker.clear(key);
                log.warn("Login rejected for inactive account: username={}", key);
                try {
                    auditLogService.logAction(user.getId(), "LOGIN_BLOCKED_INACTIVE",
                            "Inactive user login attempt blocked: " + user.getUsername(),
                            "User", user.getId(), null, null);
                } catch (Exception auditEx) {
                    log.error("Audit log failed for inactive login block. username={}", key, auditEx);
                }
                throw new BusinessException("auth.login.inactive");
            }

            attemptTracker.clear(key);

            String jwtToken = jwtUtil.generateToken(user);
            UserResponse userResponse = createUserResponse(user);

            auditLogService.logAction(user.getId(), "LOGIN_SUCCESS",
                    "User logged in successfully", "User", user.getId(), null, null);

            return new LoginResponse(jwtToken, null, 86400L, userResponse);
        } catch (BusinessException be) {
            // Inactive-account rejection is not a password failure — don't count it
            // toward the lockout, otherwise a deactivated user could lock themselves in.
            if (!"auth.login.inactive".equals(be.getMessage())) {
                countFailure(loginRequest.getUsername(), clientAddress);
            }
            // Preserve our intentional business error messages
            throw be;
        } catch (org.springframework.security.authentication.AccountStatusException ase) {
            // Covers LockedException / DisabledException / AccountExpiredException
            // from the provider's pre-auth checks — i.e. correct credentials but a
            // deactivated account (User.isActive drives all three status flags).
            // Not a password failure — no lockout penalty.
            log.warn("Login rejected for inactive account: username={}", key);
            throw new BusinessException("auth.login.inactive");
        } catch (Exception e) {
            countFailure(loginRequest.getUsername(), clientAddress);
            log.error("Login failed due to unexpected error. username={}", loginRequest.getUsername(), e);

            // Best-effort audit log; do not mask the root exception with audit issues
            try {
                User user = userRepository.findByUsernameIgnoreCase(loginRequest.getUsername()).orElse(null);
                if (user != null) {
                    auditLogService.logAction(user.getId(), "LOGIN_FAILED",
                            "Failed login attempt for user: " + loginRequest.getUsername(),
                            "User", user.getId(), null, null);
                } else {
                    auditLogService.logAction(null, "LOGIN_FAILED",
                            "Failed login attempt for non-existent user: " + loginRequest.getUsername(),
                            "User", null, null, null);
                }
            } catch (Exception auditEx) {
                log.error("Audit log failed for login failure. username={}", loginRequest.getUsername(), auditEx);
            }

            // Keep frontend behavior consistent
            throw new BusinessException("auth.login.failed");
        }
    }

    /**
     * Counts one failed sign-in. The address always pays, because that is what stops
     * somebody from walking through the username list from another machine; the username
     * only pays when it belongs to an account, so a script of invented names cannot buy
     * keys in the tracker and never shuts a real account.
     */
    private void countFailure(String username, String clientAddress) {
        attemptTracker.recordFailure(LoginAttemptTracker.addressKey(clientAddress),
                loginPolicy.addressPolicy());
        if (userRepository.findByUsernameIgnoreCase(username).isPresent()) {
            attemptTracker.recordFailure(attemptKey(username, clientAddress), LoginAttemptTracker.LOGIN);
        }
    }


    public User registerUser(RegisterRequest registerRequest) {
        if (userRepository.existsByUsername(registerRequest.getUsername())) {
            throw new BusinessException("validation.duplicate.username");
        }
        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            throw new BusinessException("validation.duplicate.email");
        }

        Role defaultRole = roleRepository.findByName(Role.RoleName.ROLE_CASHIER)
                .orElseThrow(() -> new BusinessException("Default role not found"));

        User user = buildUser(registerRequest, defaultRole);

        User savedUser = userRepository.save(user);

        auditLogService.logAction(savedUser.getId(), "USER_REGISTER", 
            "New user registered: " + savedUser.getUsername(), 
            "User", savedUser.getId(), null, savedUser.toString());

        return savedUser;
    }

    public User registerFirstAdmin(RegisterRequest registerRequest) {
        synchronized (firstAdminLock) {
            // Re-check inside the lock so concurrent first-admin registrations cannot
            // both pass the count() check (TOCTOU) and create two admins.
            if (userRepository.count() > 0) {
                throw new BusinessException("auth.firstadmin.notallowed");
            }
            if (userRepository.existsByUsername(registerRequest.getUsername())) {
                throw new BusinessException("validation.duplicate.username");
            }
            if (userRepository.existsByEmail(registerRequest.getEmail())) {
                throw new BusinessException("validation.duplicate.email");
            }

            Role adminRole = roleRepository.findByName(Role.RoleName.ROLE_ADMIN)
                    .orElseThrow(() -> new BusinessException("Admin role not found"));

            User user = buildUser(registerRequest, adminRole);

            User savedUser = userRepository.save(user);

            auditLogService.logAction(savedUser.getId(), "USER_REGISTER",
                    "First admin user registered: " + savedUser.getUsername(),
                    "User", savedUser.getId(), null, savedUser.toString());

            return savedUser;
        }
    }

    private User buildUser(RegisterRequest request, Role role) {
        User user = new User();
        user.setUsername(request.getUsername());
        user.setUsernameNormalized(normalizeKey(request.getUsername()));
        user.setEmail(request.getEmail());
        user.setEmailNormalized(normalizeKey(request.getEmail()));
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPhone("");
        user.setRole(role);
        user.setIsActive(true);
        user.setTokenVersion(0);
        return user;
    }

    private static String normalizeKey(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }

    private UserResponse createUserResponse(User user) {
        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmail());
        response.setFirstName(user.getFirstName());
        response.setLastName(user.getLastName());
        response.setPhone(user.getPhone());
        response.setRoleName(user.getRole().getName().name());
        response.setIsActive(user.getIsActive());
        response.setPreferredLanguage(user.getPreferredLanguage());
        response.setCreatedAt(user.getCreatedAt());
        response.setUpdatedAt(user.getUpdatedAt());
        return response;
    }
}


