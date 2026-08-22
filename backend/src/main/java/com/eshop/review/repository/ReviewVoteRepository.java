package com.eshop.review.repository;

import com.eshop.review.domain.ReviewVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewVoteRepository extends JpaRepository<ReviewVote, ReviewVote.Key> {

    default Optional<ReviewVote> findVote(UUID reviewId, UUID userId) {
        return findById(new ReviewVote.Key(reviewId, userId));
    }
}
