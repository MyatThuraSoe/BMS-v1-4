package com.bms.service;

import com.bms.dto.request.UserUpdateRequest;
import com.bms.dto.response.UserStatsDto;
import com.bms.entity.Role;
import com.bms.entity.User;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.RoleRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final SaleRepository saleRepository;

    private final PasswordEncoder passwordEncoder;

    private final AuditLogService auditLogService;

    public UserService(UserRepository userRepository,
                        RoleRepository roleRepository,
                        SaleRepository saleRepository,
                        PasswordEncoder passwordEncoder,
                        AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.saleRepository = saleRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Load regardless of isActive — an inactive account must reach the
        // authentication provider so it fails with DisabledException (surfaced
        // to the user as "contact administrator"), not as "invalid credentials".
        User user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
        if (user.getDeletedAt() != null) {
            // Soft-deleted users are treated as non-existent
            throw new UsernameNotFoundException("User not found: " + username);
        }
        return user;
    }

    public User createUser(com.bms.dto.request.UserCreateRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("Username already exists");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already exists");
        }

        Role role = roleRepository.findById(request.getRoleId())
                .orElseThrow(() -> new ResourceNotFoundException("Role not found"));

        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPhone(request.getPhone());
        user.setRole(role);
        user.setIsActive(true);

        User savedUser = userRepository.save(user);
        
        auditLogService.logAction(savedUser.getId(), "USER_CREATE", 
            "User created: " + savedUser.getUsername(), 
            "User", savedUser.getId(), null, savedUser.toString());

        return savedUser;
    }

    public User updateUser(Long userId, UserUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!user.getUsername().equals(request.getUsername()) && 
            userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("Username already exists");
        }
        if (!user.getEmail().equals(request.getEmail()) && 
            userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already exists");
        }


        Role role = roleRepository.findById(request.getRoleId())
                .orElseThrow(() -> new ResourceNotFoundException("Role not found"));

        String oldValues = user.toString();

        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPhone(request.getPhone());
        user.setRole(role);
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }
        if (request.getIsActive() != null) {
            // Guard: an admin deactivating their own account would lock themselves
            // out with no one left to re-enable them.
            if (Boolean.FALSE.equals(request.getIsActive())
                    && user.getUsername().equals(currentUsername())) {
                throw new com.bms.exception.BusinessException("auth.user.self.deactivate");
            }
            user.setIsActive(request.getIsActive());
        }

        User updatedUser = userRepository.save(user);
        
        auditLogService.logAction(updatedUser.getId(), "USER_UPDATE", 
            "User updated: " + updatedUser.getUsername(), 
            "User", updatedUser.getId(), oldValues, updatedUser.toString());

        return updatedUser;
    }

    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        user.setDeletedAt(java.time.LocalDateTime.now());
        user.setIsActive(false);
        userRepository.save(user);
        
        auditLogService.logAction(user.getId(), "USER_DELETE", 
            "User deleted: " + user.getUsername(), 
            "User", user.getId(), user.toString(), null);
    }

    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private String currentUsername() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    public void changeOwnPassword(String username, com.bms.dto.request.ChangePasswordRequest request) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new com.bms.exception.BusinessException("auth.current.password.incorrect");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        auditLogService.logAction(user.getId(), "PASSWORD_CHANGE", "User changed their own password", "User", user.getId(), null, null);
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public User updatePreferredLanguage(String username, String lang) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setPreferredLanguage(lang);
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public UserStatsDto getUserSalesStats(Long userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        SaleRepository.CashierStats allTime = saleRepository.findCashierStats(userId);
        SaleRepository.CashierStats today = saleRepository.findCashierStatsSince(userId, todayStart);

        return new UserStatsDto(
                allTime.getTotalSales(),
                allTime.getTotalRevenue(),
                today.getTotalSales(),
                today.getTotalRevenue()
        );
    }
}
