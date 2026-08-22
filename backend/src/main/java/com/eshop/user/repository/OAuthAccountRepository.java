package com.eshop.user.repository;

import com.eshop.user.domain.OAuthAccount;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, UUID> {

    @EntityGraph(attributePaths = {"user", "user.roles", "user.roles.permissions"})
    Optional<OAuthAccount> findByProviderAndProviderUserId(OAuthAccount.Provider provider,
                                                           String providerUserId);

    List<OAuthAccount> findByUserId(UUID userId);

    boolean existsByUserIdAndProvider(UUID userId, OAuthAccount.Provider provider);
}
