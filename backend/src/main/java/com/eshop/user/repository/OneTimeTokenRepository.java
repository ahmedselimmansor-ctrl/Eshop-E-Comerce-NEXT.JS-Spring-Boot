package com.eshop.user.repository;

import com.eshop.user.domain.OneTimeToken;
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
public interface OneTimeTokenRepository extends JpaRepository<OneTimeToken, UUID> {

    @EntityGraph(attributePaths = "user")
    Optional<OneTimeToken> findByTokenHashAndType(String tokenHash, OneTimeToken.Type type);

    @EntityGraph(attributePaths = "user")
    @Query("""
            SELECT t FROM OneTimeToken t
             WHERE t.user.id = :userId AND t.type = :type AND t.consumedAt IS NULL
             ORDER BY t.createdAt DESC
             LIMIT 1
            """)
    Optional<OneTimeToken> findLatestPending(@Param("userId") UUID userId,
                                             @Param("type") OneTimeToken.Type type);

    /**
     * Invalidates any outstanding token of the same kind before a new one is issued, so requesting a
     * second password-reset link silently retires the first instead of leaving two valid.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE OneTimeToken t SET t.consumedAt = :now
             WHERE t.user.id = :userId AND t.type = :type AND t.consumedAt IS NULL
            """)
    int consumeOutstanding(@Param("userId") UUID userId,
                           @Param("type") OneTimeToken.Type type,
                           @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM OneTimeToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
