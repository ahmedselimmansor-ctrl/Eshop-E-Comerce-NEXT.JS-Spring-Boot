package com.eshop.user.repository;

import com.eshop.user.domain.RefreshToken;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * Looks a token up by hash, including revoked and expired rows.
     *
     * <p>Deliberately not filtered to usable tokens: presenting a revoked token is the signal that
     * a family has been compromised, and filtering it out here would hide the very case reuse
     * detection exists to catch.
     */
    @EntityGraph(attributePaths = {"user", "user.roles", "user.roles.permissions"})
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByFamilyId(UUID familyId);

    /** Revokes an entire rotation family - the response to a detected token replay. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    /** Signs the user out everywhere: password change, suspension, self-service "log out all". */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.user.id = :userId AND t.revokedAt IS NULL")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    @Query("""
            SELECT t FROM RefreshToken t
             WHERE t.user.id = :userId AND t.revokedAt IS NULL AND t.expiresAt > :now
             ORDER BY t.createdAt DESC
            """)
    List<RefreshToken> findActiveSessions(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
