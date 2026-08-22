package com.eshop.cart.repository;

import com.eshop.cart.domain.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    /**
     * Removes a line only if it belongs to the given cart, so a stray item id from another
     * shopper's cart resolves to "nothing deleted" rather than a cross-cart mutation.
     */
    @Modifying
    @Query("DELETE FROM CartItem i WHERE i.id = :itemId AND i.cart.id = :cartId")
    int deleteFromCart(@Param("itemId") UUID itemId, @Param("cartId") UUID cartId);

    @Modifying
    @Query("DELETE FROM CartItem i WHERE i.cart.id = :cartId")
    int deleteAllInCart(@Param("cartId") UUID cartId);

    /** Lines referencing a product that has just been archived or deleted. */
    @Query("SELECT COUNT(i) FROM CartItem i WHERE i.product.id = :productId")
    long countReferencingProduct(@Param("productId") UUID productId);
}
