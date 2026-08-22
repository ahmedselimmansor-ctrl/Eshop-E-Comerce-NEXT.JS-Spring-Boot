package com.eshop.notification.repository;

import com.eshop.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

/** In-app notifications. */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query("""
            SELECT n FROM Notification n
             WHERE n.user.id = :userId
               AND (:unreadOnly = FALSE OR n.readAt IS NULL)
             ORDER BY n.createdAt DESC
            """)
    Page<Notification> findForUser(@Param("userId") UUID userId,
                                   @Param("unreadOnly") boolean unreadOnly,
                                   Pageable pageable);

    /** Backs the unread badge — one indexed count, not a full page fetch. */
    @Query("SELECT COUNT(n) FROM Notification n WHERE n.user.id = :userId AND n.readAt IS NULL")
    long countUnread(@Param("userId") UUID userId);

    /** Scoped by owner so a stray id cannot mark someone else's notification read. */
    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.id = :id AND n.user.id = :userId AND n.readAt IS NULL")
    int markRead(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.user.id = :userId AND n.readAt IS NULL")
    int markAllRead(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM Notification n WHERE n.createdAt < :cutoff AND n.readAt IS NOT NULL")
    int deleteReadBefore(@Param("cutoff") Instant cutoff);
}
