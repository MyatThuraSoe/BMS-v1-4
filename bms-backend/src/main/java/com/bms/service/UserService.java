package com.bms.service;

import com.bms.dto.request.ChangePasswordRequest;
import com.bms.dto.request.CloseShiftRequest;
import com.bms.dto.request.UserCreateRequest;
import com.bms.dto.request.UserUpdateRequest;
import com.bms.dto.response.UserStatsDto;
import com.bms.entity.CashDrawerSession;
import com.bms.entity.CashShift;
import com.bms.entity.DrawerSessionStatus;
import com.bms.entity.Role;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.CashDrawerSessionRepository;
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
import java.util.Objects;
import java.util.Optional;

@Service
@Transactional
public class UserService implements UserDetailsService {

    @Autowired
    private ShopTimeService shopTimeService;

    /** T6: a password change issues the caller its own replacement token. */
    @Autowired
    private com.bms.config.JwtUtil jwtUtil;

    /** Who may not sign in right now. In memory, so an admin can clear it without a restart. */
    @Autowired
    private LoginAttemptTracker attemptTracker;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final SaleRepository saleRepository;
    private final CashShiftRepository cashShiftRepository;
    private final CashShiftService cashShiftService;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final CashDrawerSessionRepository cashDrawerSessionRepository;

    public UserService(UserRepository userRepository,
                      RoleRepository roleRepository,
                      SaleRepository saleRepository,
                      CashShiftRepository cashShiftRepository,
                      CashShiftService cashShiftService,
                      PasswordEncoder passwordEncoder,
                      AuditLogService auditLogService,
                      CashDrawerSessionRepository cashDrawerSessionRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.saleRepository = saleRepository;
        this.cashShiftRepository = cashShiftRepository;
        this.cashShiftService = cashShiftService;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
        this.cashDrawerSessionRepository = cashDrawerSessionRepository;
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
        if (userRepository.existsByUsernameNormalized(normalizedUsername)
                || userRepository.existsByUsernameIgnoreCase(request.getUsername())) {
            throw new IllegalArgumentException("Username already exists");
        }
        if (userRepository.existsByEmailNormalized(normalizedEmail)
                || userRepository.existsByEmailIgnoreCase(request.getEmail())) {
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

        // An identity that is not really changing must not be reported as a
        // duplicate of itself. The stored username is compared because a row that
        // predates the normalized columns has a null key and would otherwise look
        // like a rename on every save (and a method call on that null threw 500).
        // The ignore-case checks catch the rows whose key is still not backfilled.
        if (!sameIdentity(user.getUsername(), user.getUsernameNormalized(), normalizedUsername)
                && (userRepository.existsByUsernameNormalized(normalizedUsername)
                    || userRepository.existsOtherByUsernameIgnoreCase(requestedUsername, userId))) {
            throw new IllegalArgumentException("Username already exists");
        }
        if (!sameIdentity(user.getEmail(), user.getEmailNormalized(), normalizedEmail)
                && (userRepository.existsByEmailNormalized(normalizedEmail)
                    || userRepository.existsOtherByEmailIgnoreCase(requestedEmail, userId))) {
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
                closeUserOpenShift(openShift.get().getId(), "User deactivated", currentUserId());
            }
            assertNoOpenDrawerOwnedBy(userId);
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
        // Bump token version when a non-admin user is promoted to admin — must
        // check the *old* role, before setRole overwrites it.
        if (!isAdmin(user) && newRoleIsAdmin) {
            bumpTokenVersion(user);
        }
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
            closeUserOpenShift(openShift.get().getId(), "User deleted", actingUserId);
        }
        assertNoOpenDrawerOwnedBy(userId);

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

    /**
     * Clears a failed-login lock. The lock is only ever in memory, so this is the remedy for
     * the person who mistyped their own password five times, and it is deliberately not
     * available to anyone but an admin on someone else's account.
     *
     * <p>Every address the account was locked at is freed, because the admin asking is not
     * the machine that was guessing.
     */
    public User unlockLogin(Long userId, Long actingUserId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        attemptTracker.clearUsername(user.getUsername());

        auditLogService.logAction(actingUserId != null ? actingUserId : user.getId(),
            "USER_LOGIN_UNLOCKED",
            "Login lock cleared for user: " + user.getUsername()
                    + " by acting user " + actingUserId,
            "User", user.getId(), null, null);
        return user;
    }

    /**
     * Whether signing in is currently refused for this account from somewhere, whatever
     * the password is. Which address is shut is not said: the answer is for an admin's
     * list, and a lockout that names the attacker's machine is not worth a log line.
     */
    public boolean isLoginLocked(User user) {
        return attemptTracker.isLockedForUsername(user.getUsername());
    }

    /** Minutes left on that refusal, at least one, and zero when there is no lock. */
    public long loginLockedMinutes(User user) {
        return attemptTracker.minutesRemainingForUsername(user.getUsername());
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

    public String changeOwnPassword(String username, ChangePasswordRequest request) {
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
        // T6: the version bump above is what logs every other device out, and it
        // invalidates this request's own token too, so the session making the
        // change has to leave with a replacement.
        return jwtUtil.generateToken(user);
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

    /**
     * A drawer that is still open belongs to the person who opened it: only its owner
     * or a manager can close it. Disabling the account strands the float, and the next
     * shift cannot open that drawer either. The open-shift check above does not cover
     * this, because a shift can be closed while it leaves the drawer open.
     */
    private void assertNoOpenDrawerOwnedBy(Long userId) {
        cashDrawerSessionRepository.findFirstByOpenedByIdAndStatus(userId, DrawerSessionStatus.OPEN)
                .ifPresent(session -> {
                    throw new BusinessException("This user still holds open cash drawer session "
                            + session.getId() + ". Close the drawer before deactivating or deleting them.");
                });
    }

    private void closeUserOpenShift(Long shiftId, String reason, Long actingUserId) {
        if (shiftId == null) {
            return;
        }
        CashShift shift = cashShiftRepository.findById(shiftId).orElse(null);
        if (shift == null || !"OPEN".equals(shift.getStatus())) {
            return;
        }
        // N6: a force-close never counts the float, so when this shift is the
        // only one left on the drawer it would leave the drawer session open
        // with nobody attached. The manager has to close the drawer properly.
        CashDrawerSession session = shift.getDrawerSession();
        if (session != null && session.getStatus() == DrawerSessionStatus.OPEN) {
            boolean sharesDrawer = cashShiftRepository
                    .findByDrawerSessionIdAndStatus(session.getId(), "OPEN").stream()
                    .anyMatch(other -> !Objects.equals(other.getId(), shift.getId()));
            if (!sharesDrawer) {
                throw new BusinessException("Close the drawer first, then retry");
            }
        }

        CloseShiftRequest request = new CloseShiftRequest();
        request.setForceClose(true);
        request.setForceReason(reason);
        request.setCloseDrawer(Boolean.FALSE);
        // The manager who pressed the button is the actor, otherwise the audit
        // reads as if the cashier had closed their own shift.
        Long actor = actingUserId != null ? actingUserId : currentUserId();
        cashShiftService.closeShift(shiftId, request,
                actor != null ? actor : shift.getCashierId(), true);
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

    private static String normalizeKey(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }

    private static boolean sameIdentity(String storedValue, String storedKey, String requestedKey) {
        return Objects.equals(normalizeKey(storedValue), requestedKey)
                || Objects.equals(storedKey, requestedKey);
    }

    private String trimmed(String value) {
        return value == null ? null : value.trim();
    }
}
