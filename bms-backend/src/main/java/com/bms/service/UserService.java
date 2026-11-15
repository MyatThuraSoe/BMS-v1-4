package com.bms.service;

import com.bms.dto.request.ChangePasswordRequest;
import com.bms.dto.request.CloseShiftRequest;
import com.bms.dto.request.UserCreateRequest;
import com.bms.dto.request.UserUpdateRequest;
import com.bms.dto.response.UserStatsDto;
import com.bms.entity.CashShift;
import com.bms.entity.Role;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.CashShiftRepository;
import com.bms.repository.RoleRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class UserService implements UserDetailsService {

    @Autowired
    private ShopTimeService shopTimeService = new ShopTimeService(null, "UTC");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final SaleRepository saleRepository;
    private final CashShiftRepository cashShiftRepository;
    private final CashShiftService cashShiftService;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public UserService(UserRepository userRepository,
                      RoleRepository roleRepository,
                      SaleRepository saleRepository,
                      CashShiftRepository cashShiftRepository,
                      CashShiftService cashShiftService,
                      PasswordEncoder passwordEncoder,
                      AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.saleRepository = saleRepository;
        this.cashShiftRepository = cashShiftRepository;
        this.cashShiftService = cashShiftService;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String normalized = normalizeKey(username);
        User user = userRepository.findByUsernameNormalized(normalized)
                .or(() -> userRepository.findByUsernameIgnoreCase(username))
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
        if (user.getDeletedAt() != null) {
            throw new UsernameNotFoundException("User not found: " + username);
        }
        return user;
    }

    public User createUser(UserCreateRequest request) {
        String normalizedUsername = normalizeKey(request.getUsername());
        String normalizedEmail = normalizeKey(request.getEmail());
        if (userRepository.existsByUsernameNormalized(normalizedUsername)) {
            throw new IllegalArgumentException("Username already exists");
        }
        if (userRepository.existsByEmailNormalized(normalizedEmail)) {
            throw new IllegalArgumentException("Email already exists");
        }

        Role role = roleRepository.findById(request.getRoleId())
                .orElseThrow(() -> new ResourceNotFoundException("Role not found"));

        User user = new User();
        user.setUsername(trimmed(request.getUsername()));
        user.setUsernameNormalized(normalizedUsername);
        user.setEmail(trimmed(request.getEmail()));
        user.setEmailNormalized(normalizedEmail);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPhone(request.getPhone());
        user.setRole(role);
        user.setIsActive(true);
        user.setTokenVersion(0);

        User savedUser = userRepository.save(user);

        auditLogService.logAction(savedUser.getId(), "USER_CREATE",
            "User created: " + savedUser.getUsername(),
            "User", savedUser.getId(), null, savedUser.toString());
        return savedUser;
    }

    public User updateUser(Long userId, UserUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String requestedUsername = trimmed(request.getUsername());
        String requestedEmail = trimmed(request.getEmail());
        String normalizedUsername = normalizeKey(requestedUsername);
        String normalizedEmail = normalizeKey(requestedEmail);

        if (!user.getUsernameNormalized().equals(normalizedUsername)
                && userRepository.existsByUsernameNormalized(normalizedUsername)) {
            throw new IllegalArgumentException("Username already exists");
        }
        if (!user.getEmailNormalized().equals(normalizedEmail)
                && userRepository.existsByEmailNormalized(normalizedEmail)) {
            throw new IllegalArgumentException("Email already exists");
        }

        Role role = roleRepository.findById(request.getRoleId())
                .orElseThrow(() -> new ResourceNotFoundException("Role not found"));

        boolean targetIsAdmin = isAdmin(user);
        boolean newRoleIsAdmin = Role.RoleName.ROLE_ADMIN.equals(role.getName());
        if (targetIsAdmin && !newRoleIsAdmin && isLastActiveAdmin()) {
            throw new BusinessException("auth.lastadmin.demote");
        }

        String oldValues = user.toString();
        boolean passwordChanged = request.getPassword() != null && !request.getPassword().isBlank();
        boolean deactivateRequested = request.getIsActive() != null && Boolean.FALSE.equals(request.getIsActive());
        if (deactivateRequested) {
            Optional<CashShift> openShift = cashShiftRepository.findByCashierIdAndStatus(userId, "OPEN");
            if (openShift.isPresent()) {
                if (!Boolean.TRUE.equals(request.getCloseOpenShifts())) {
                    throw new BusinessException("Open shift for user: " + openShift.get().getId() + "; close it before deactivation");
                }
                closeUserOpenShift(userId, openShift.get().getId(), "User deactivated");
            }
        }
        if (Boolean.FALSE.equals(request.getIsActive()) && user.getUsername().equals(currentUsername())) {
            throw new BusinessException("auth.user.self.deactivate");
        }
        if (Boolean.FALSE.equals(request.getIsActive()) && targetIsAdmin && isLastActiveAdmin()) {
            throw new BusinessException("auth.lastadmin.deactivate");
        }

        user.setUsername(requestedUsername);
        user.setUsernameNormalized(normalizedUsername);
        user.setEmail(requestedEmail);
        user.setEmailNormalized(normalizedEmail);
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPhone(request.getPhone());
        user.setRole(role);
        if (passwordChanged) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
            bumpTokenVersion(user);
        }
        if (request.getIsActive() != null) {
            user.setIsActive(request.getIsActive());
            if (Boolean.FALSE.equals(request.getIsActive())) {
                bumpTokenVersion(user);
            }
        }
        if (!isAdmin(user) && newRoleIsAdmin) {
            bumpTokenVersion(user);
        }

        User updatedUser = userRepository.save(user);
        auditLogService.logAction(updatedUser.getId(), "USER_UPDATE",
            "User updated: " + updatedUser.getUsername(),
            "User", updatedUser.getId(), oldValues, updatedUser.toString());
        return updatedUser;
    }

    public void deleteUser(Long userId) {
        deleteUser(userId, false, null);
    }

    public void deleteUser(Long userId, boolean closeOpenShifts, Long actingUserId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Long currentUserId = currentUserId();
        if (currentUserId != null && currentUserId.equals(userId)) {
            throw new BusinessException("auth.user.self.delete");
        }
        if (actingUserId != null && actingUserId.equals(userId)) {
            throw new BusinessException("auth.user.self.delete");
        }
        if (isAdmin(user) && isLastActiveAdmin()) {
            throw new BusinessException("auth.lastadmin.delete");
        }

        Optional<CashShift> openShift = cashShiftRepository.findByCashierIdAndStatus(userId, "OPEN");
        if (openShift.isPresent()) {
            if (!closeOpenShifts) {
                throw new BusinessException("Open shift for user: " + openShift.get().getId() + "; close it before delete");
            }
            closeUserOpenShift(userId, openShift.get().getId(), "User deleted");
        }

        user.setUsername(com.bms.util.SoftDeleteKeys.release(user.getUsername(), user.getId(), 255));
        user.setUsernameNormalized(com.bms.util.SoftDeleteKeys.release(user.getUsernameNormalized(), user.getId(), 255));
        user.setEmail(com.bms.util.SoftDeleteKeys.release(user.getEmail(), user.getId(), 255));
        user.setEmailNormalized(com.bms.util.SoftDeleteKeys.release(user.getEmailNormalized(), user.getId(), 255));
        user.setDeletedAt(shopTimeService.now());
        user.setIsActive(false);
        bumpTokenVersion(user);
        userRepository.save(user);

        auditLogService.logAction(actingUserId != null ? actingUserId : user.getId(), "USER_DELETE",
            "User deleted: " + user.getUsername(),
            "User", user.getId(), user.toString(), null);
    }

    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    public User findByUsername(String username) {
        String normalized = normalizeKey(username);
        return userRepository.findByUsernameNormalized(normalized)
                .or(() -> userRepository.findByUsernameIgnoreCase(username))
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    public void changeOwnPassword(String username, ChangePasswordRequest request) {
        User user = userRepository.findByUsernameNormalized(normalizeKey(username))
                .or(() -> userRepository.findByUsernameIgnoreCase(username))
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new BusinessException("auth.current.password.incorrect");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        bumpTokenVersion(user);
        userRepository.save(user);
        auditLogService.logAction(user.getId(), "PASSWORD_CHANGE", "User changed their own password", "User", user.getId(), null, null);
    }

    public List<User> getAllUsers() {
        return getAllUsers(false);
    }

    public List<User> getAllUsers(boolean includeDeleted) {
        return includeDeleted ? userRepository.findAll() : userRepository.findByDeletedAtIsNullOrderByUsernameAsc();
    }

    public User updatePreferredLanguage(String username, String lang) {
        User user = userRepository.findByUsernameNormalized(normalizeKey(username))
                .or(() -> userRepository.findByUsernameIgnoreCase(username))
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setPreferredLanguage(lang);
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public UserStatsDto getUserSalesStats(Long userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        java.time.LocalDateTime todayStart = shopTimeService.today().atStartOfDay();
        SaleRepository.CashierStats allTime = saleRepository.findCashierStats(userId);
        SaleRepository.CashierStats today = saleRepository.findCashierStatsSince(userId, todayStart);

        return new UserStatsDto(
                allTime.getTotalSales(),
                allTime.getTotalRevenue(),
                today.getTotalSales(),
                today.getTotalRevenue()
        );
    }

    private void closeUserOpenShift(Long userId, Long shiftId, String reason) {
        if (shiftId == null) {
            return;
        }
        CashShift shift = cashShiftRepository.findById(shiftId).orElse(null);
        if (shift == null || !"OPEN".equals(shift.getStatus())) {
            return;
        }
        CloseShiftRequest request = new CloseShiftRequest();
        request.setForceClose(true);
        request.setForceReason(reason);
        request.setCloseDrawer(Boolean.FALSE);
        cashShiftService.closeShift(shiftId, request, userId, true);
    }

    private void bumpTokenVersion(User user) {
        user.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
    }

    private String currentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User principal)) {
            return null;
        }
        return principal.getId();
    }

    private boolean isAdmin(User user) {
        return user.getRole() != null && Role.RoleName.ROLE_ADMIN.equals(user.getRole().getName());
    }

    private boolean isLastActiveAdmin() {
        return userRepository.countActiveByRole(Role.RoleName.ROLE_ADMIN) <= 1;
    }

    private String normalizeKey(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }

    private String trimmed(String value) {
        return value == null ? null : value.trim();
    }
}
