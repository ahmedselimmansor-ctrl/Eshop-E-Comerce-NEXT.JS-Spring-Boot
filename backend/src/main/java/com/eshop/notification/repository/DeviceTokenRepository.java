package com.eshop.notification.repository;

import com.eshop.notification.domain.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Push tokens, one row per device per user. */
@Repository
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    Optional<DeviceToken> findByToken(String token);

    @Query("SELECT d FROM DeviceToken d WHERE d.user.id = :userId AND d.active = TRUE")
    List<DeviceToken> findActiveForUser(@Param("userId") UUID userId);

    @Modifying
    @Query("UPDATE DeviceToken d SET d.active = FALSE WHERE d.token IN :tokens")
    int deactivateAll(@Param("tokens") List<String> tokens);

    @Modifying
    @Query("DELETE FROM DeviceToken d WHERE d.active = FALSE AND d.lastSeenAt < :cutoff")
    int purgeInactiveBefore(@Param("cutoff") Instant cutoff);
}
