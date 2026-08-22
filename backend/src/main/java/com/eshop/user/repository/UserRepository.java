package com.eshop.user.repository;

import com.eshop.user.domain.User;
import com.eshop.user.domain.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Authentication lookup. The entity graph pulls roles and permissions in one query — without it
     * the eager {@code @ManyToMany} associations issue a separate select per role on the hottest
     * path in the application.
     */
    @EntityGraph(attributePaths = {"roles", "roles.permissions"})
    @Query("SELECT u FROM User u WHERE u.email = :email AND u.deletedAt IS NULL")
    Optional<User> findActiveByEmail(@Param("email") String email);

    @EntityGraph(attributePaths = {"roles", "roles.permissions"})
    @Query("SELECT u FROM User u WHERE u.phone = :phone AND u.deletedAt IS NULL")
    Optional<User> findActiveByPhone(@Param("phone") String phone);

    /** Accepts either an email or a phone number, matching the login form's single field. */
    @EntityGraph(attributePaths = {"roles", "roles.permissions"})
    @Query("""
            SELECT u FROM User u
             WHERE u.deletedAt IS NULL
               AND (u.email = :identifier OR u.phone = :identifier)
            """)
    Optional<User> findActiveByEmailOrPhone(@Param("identifier") String identifier);

    @EntityGraph(attributePaths = {"roles", "roles.permissions"})
    @Query("SELECT u FROM User u WHERE u.id = :id AND u.deletedAt IS NULL")
    Optional<User> findActiveById(@Param("id") UUID id);

    boolean existsByEmailAndDeletedAtIsNull(String email);

    boolean existsByPhoneAndDeletedAtIsNull(String phone);

    /**
     * Admin customer search.
     *
     * <p>Filters are all optional and short-circuit with {@code :param IS NULL}, which keeps one
     * query plan instead of building predicates dynamically. The name match runs against the
     * concatenated full name so "احمد محمد" matches even though the columns are separate.
     */
    @Query("""
            SELECT u FROM User u
             WHERE u.deletedAt IS NULL
               AND (CAST(:status AS STRING) IS NULL OR u.status = :status)
               AND (CAST(:query AS STRING) IS NULL OR
                    LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:query AS STRING), '%')) OR
                    u.phone LIKE CONCAT('%', CAST(:query AS STRING), '%') OR
                    LOWER(CONCAT(u.firstName, ' ', COALESCE(u.lastName, ''))) LIKE LOWER(CONCAT('%', CAST(:query AS STRING), '%')))
            """)
    Page<User> search(@Param("query") String query,
                      @Param("status") UserStatus status,
                      Pageable pageable);

    @Query("SELECT COUNT(u) FROM User u WHERE u.deletedAt IS NULL AND u.createdAt >= :since")
    long countRegisteredSince(@Param("since") Instant since);

    /**
     * Clears a lockout without loading the entity, used by the scheduled unlock sweep.
     * {@code @Modifying} with {@code clearAutomatically} keeps the persistence context from serving
     * a stale copy afterwards.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE User u
               SET u.lockedUntil = NULL, u.failedLoginAttempts = 0
             WHERE u.lockedUntil IS NOT NULL AND u.lockedUntil < :now
            """)
    int clearExpiredLockouts(@Param("now") Instant now);
}
