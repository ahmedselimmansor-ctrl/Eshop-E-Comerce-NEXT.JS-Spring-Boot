package com.eshop.catalog.repository;

import com.eshop.catalog.domain.ProductVariant;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

    @EntityGraph(attributePaths = {"product", "attributeValues", "attributeValues.attributeValue"})
    Optional<ProductVariant> findDetailById(UUID id);

    Optional<ProductVariant> findBySku(String sku);

    boolean existsBySku(String sku);

    List<ProductVariant> findByProductIdOrderByPositionAsc(UUID productId);

    /**
     * Guards against a variant id from one product being submitted against another - the cart and
     * checkout both verify the pairing rather than trusting the client's association.
     */
    @Query("SELECT v FROM ProductVariant v WHERE v.id = :variantId AND v.product.id = :productId")
    Optional<ProductVariant> findByIdAndProductId(@Param("variantId") UUID variantId,
                                                  @Param("productId") UUID productId);

    long countByProductId(UUID productId);
}
