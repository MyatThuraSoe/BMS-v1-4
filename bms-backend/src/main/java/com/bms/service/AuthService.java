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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);

    private final Map<String, AttemptRecord> loginAttempts = new ConcurrentHashMap<>();
    private final Object firstAdminLock = new Object();

    private record AttemptRecord(int failures, Instant lastAttempt, Instant lockedUntil) {}

    private String attemptKey(String username) {
        return username == null ? "" : username.trim().toLowerCase();
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
        String key = attemptKey(loginRequest.getUsername());

        AttemptRecord frozen = loginAttempts.get(key);
        if (frozen != null && frozen.lockedUntil() != null && Instant.now().isBefore(frozen.lockedUntil())) {
            log.warn("Login rejected for locked account: username={}", key);
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
                loginAttempts.remove(key);
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

            loginAttempts.remove(key);

            String jwtToken = jwtUtil.generateToken(user.getUsername());
            UserResponse userResponse = createUserResponse(user);

            auditLogService.logAction(user.getId(), "LOGIN_SUCCESS",
                    "User logged in successfully", "User", user.getId(), null, null);

            return new LoginResponse(jwtToken, null, 86400L, userResponse);
        } catch (BusinessException be) {
            // Inactive-account rejection is not a password failure — don't count it
            // toward the lockout, otherwise a deactivated user could lock themselves in.
            if (!"auth.login.inactive".equals(be.getMessage())) {
                recordFailedAttempt(key);
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
            recordFailedAttempt(key);
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

    private void recordFailedAttempt(String key) {
        loginAttempts.merge(key, new AttemptRecord(1, Instant.now(), null), (existing, ignored) -> {
            if (existing.lockedUntil() != null && Instant.now().isBefore(existing.lockedUntil())) {
                return existing;
            }
            boolean withinWindow = existing.lastAttempt().isAfter(Instant.now().minus(ATTEMPT_WINDOW));
            int failures = withinWindow ? existing.failures() + 1 : 1;
            Instant lockedUntil = failures >= MAX_FAILED_ATTEMPTS ? Instant.now().plus(LOCKOUT_DURATION) : null;
            return new AttemptRecord(failures, Instant.now(), lockedUntil);
        });
        AttemptRecord cur = loginAttempts.get(key);
        if (cur != null && cur.lockedUntil() != null && Instant.now().isBefore(cur.lockedUntil())) {
            log.warn("Account locked after repeated failed login attempts: username={}", key);
        }
    }

    private User buildUser(RegisterRequest request, Role role) {
        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPhone("");
        user.setRole(role);
        user.setIsActive(true);
        return user;
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


