package com.eshop.user.repository;

import com.eshop.user.domain.Address;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AddressRepository extends JpaRepository<Address, UUID> {

    @Query("""
            SELECT a FROM Address a
             WHERE a.user.id = :userId AND a.deletedAt IS NULL
             ORDER BY a.defaultShipping DESC, a.createdAt DESC
            """)
    List<Address> findAllForUser(@Param("userId") UUID userId);

    /**
     * Scopes the lookup by owner in the query itself.
     *
     * <p>Loading by id and comparing the owner afterwards is the pattern that produces IDOR bugs the
     * first time someone forgets the check; making ownership part of the predicate means a mismatch
     * is an empty {@code Optional} and the caller's existing 404 path handles it.
     */
    @Query("SELECT a FROM Address a WHERE a.id = :id AND a.user.id = :userId AND a.deletedAt IS NULL")
    Optional<Address> findByIdForUser(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("""
            SELECT a FROM Address a
             WHERE a.user.id = :userId AND a.defaultShipping = TRUE AND a.deletedAt IS NULL
            """)
    Optional<Address> findDefaultShipping(@Param("userId") UUID userId);

    /** Clears the current default before promoting another, honouring the partial unique index. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Address a SET a.defaultShipping = FALSE
             WHERE a.user.id = :userId AND a.defaultShipping = TRUE AND a.deletedAt IS NULL
            """)
    int clearDefaultShipping(@Param("userId") UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Address a SET a.defaultBilling = FALSE
             WHERE a.user.id = :userId AND a.defaultBilling = TRUE AND a.deletedAt IS NULL
            """)
    int clearDefaultBilling(@Param("userId") UUID userId);

    long countByUserIdAndDeletedAtIsNull(UUID userId);
}
