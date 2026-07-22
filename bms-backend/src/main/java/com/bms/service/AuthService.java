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

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);


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
                throw new BusinessException("Invalid credentials");
            }

            String jwtToken = jwtUtil.generateToken(user.getUsername());
            UserResponse userResponse = createUserResponse(user);

            auditLogService.logAction(user.getId(), "LOGIN_SUCCESS",
                    "User logged in successfully", "User", user.getId(), null, null);

            return new LoginResponse(jwtToken, null, 86400L, userResponse);
        } catch (BusinessException be) {
            // Preserve our intentional business error messages
            throw be;
        } catch (Exception e) {
            log.error("Login failed due to unexpected error. username={}", loginRequest.getUsername(), e);

            // Best-effort audit log; do not mask the root exception with audit issues
            try {
                User user = userRepository.findByUsername(loginRequest.getUsername()).orElse(null);
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
            throw new BusinessException("Invalid credentials");
        }
    }


    public User registerUser(RegisterRequest registerRequest) {
        if (userRepository.existsByUsername(registerRequest.getUsername())) {
            throw new BusinessException("Username already exists");
        }
        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            throw new BusinessException("Email already exists");
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
        if (userRepository.count() > 0) {
            throw new BusinessException("First admin registration is only allowed when no users exist");
        }
        if (userRepository.existsByUsername(registerRequest.getUsername())) {
            throw new BusinessException("Username already exists");
        }
        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            throw new BusinessException("Email already exists");
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
        response.setCreatedAt(user.getCreatedAt());
        response.setUpdatedAt(user.getUpdatedAt());
        return response;
    }
}


