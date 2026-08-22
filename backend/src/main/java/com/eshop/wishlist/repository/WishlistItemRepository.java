package com.eshop.wishlist.repository;

import com.eshop.wishlist.domain.WishlistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface WishlistItemRepository extends JpaRepository<WishlistItem, UUID> {

    long countByWishlistId(UUID wishlistId);
}
