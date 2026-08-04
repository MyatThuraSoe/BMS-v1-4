package com.bms.repository;

import com.bms.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);


    
    @Query("SELECT u FROM User u WHERE u.username = :username AND u.isActive = true AND u.deletedAt IS NULL")
    Optional<User> findActiveByUsername(@Param("username") String username);
    
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
}