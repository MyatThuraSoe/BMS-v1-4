package com.bms.repository;

import com.bms.entity.Role;
import com.bms.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByUsernameNormalized(String usernameNormalized);
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailNormalized(String emailNormalized);
    boolean existsByUsername(String username);
    boolean existsByUsernameNormalized(String usernameNormalized);
    boolean existsByEmail(String email);
    boolean existsByEmailNormalized(String emailNormalized);

    List<User> findByDeletedAtIsNullOrderByUsernameAsc();

    @Query("SELECT u FROM User u WHERE u.username = :username AND u.isActive = true AND u.deletedAt IS NULL")
    Optional<User> findActiveByUsername(@Param("username") String username);

    @Query("SELECT u FROM User u WHERE u.usernameNormalized = :usernameNormalized AND u.isActive = true AND u.deletedAt IS NULL")
    Optional<User> findActiveByUsernameNormalized(@Param("usernameNormalized") String usernameNormalized);
    
    @Query("SELECT u FROM User u WHERE u.email = :email AND u.isActive = true AND u.deletedAt IS NULL")
    Optional<User> findActiveByEmail(@Param("email") String email);

    // ✅ Fix A4: Pessimistic write lock to serialize concurrent "open shift" requests for the same user
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    // Add this right below your existing findActiveByUsername
    @Query("SELECT u FROM User u WHERE LOWER(u.username) = LOWER(:username) AND u.isActive = true AND u.deletedAt IS NULL")
    Optional<User> findActiveByUsernameIgnoreCase(@Param("username") String username);

    // Add this right below your existing findByUsername
    @Query("SELECT u FROM User u WHERE LOWER(u.username) = LOWER(:username)")
    Optional<User> findByUsernameIgnoreCase(@Param("username") String username);

    // N2 safety net: rows that predate the normalized columns have null there,
    // so the normalized duplicate check alone would let "ADMIN" through.
    @Query("SELECT COUNT(u) > 0 FROM User u WHERE LOWER(u.username) = LOWER(:username)")
    boolean existsByUsernameIgnoreCase(@Param("username") String username);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE LOWER(u.email) = LOWER(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE LOWER(u.username) = LOWER(:username) AND u.id <> :id")
    boolean existsOtherByUsernameIgnoreCase(@Param("username") String username, @Param("id") Long id);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE LOWER(u.email) = LOWER(:email) AND u.id <> :id")
    boolean existsOtherByEmailIgnoreCase(@Param("email") String email, @Param("id") Long id);

    // Active (non-deleted, non-deactivated) users holding the given role —
    // used by the last-administrator guards.
    @Query("SELECT COUNT(u) FROM User u WHERE u.deletedAt IS NULL AND u.isActive = true AND u.role.name = :role")
    long countActiveByRole(@Param("role") Role.RoleName role);
}