package com.eshop.catalog.repository;

import com.eshop.catalog.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    Optional<Category> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /**
     * Whole tree in one query, ordered so a client can rebuild the hierarchy in a single pass by
     * appending each node to its parent - ancestors always precede descendants because {@code level}
     * sorts first.
     */
    @Query("""
            SELECT c FROM Category c
             WHERE (:activeOnly = FALSE OR c.active = TRUE)
             ORDER BY c.level ASC, c.position ASC, c.nameAr ASC
            """)
    List<Category> findTree(@Param("activeOnly") boolean activeOnly);

    @Query("""
            SELECT c FROM Category c
             WHERE c.parent IS NULL AND (:activeOnly = FALSE OR c.active = TRUE)
             ORDER BY c.position ASC, c.nameAr ASC
            """)
    List<Category> findRoots(@Param("activeOnly") boolean activeOnly);

    List<Category> findByParentIdOrderByPositionAsc(UUID parentId);

    /**
     * This category and everything beneath it, via a prefix scan on the materialised path.
     * Backed by {@code idx_categories_path (path text_pattern_ops)}.
     */
    @Query("""
            SELECT c FROM Category c
             WHERE c.id = :id OR c.path LIKE CONCAT(:path, '/%')
            """)
    List<Category> findSelfAndDescendants(@Param("id") UUID id, @Param("path") String path);

    @Query(value = """
            SELECT id FROM categories
             WHERE id = :id OR path LIKE :path || '/%'
            """, nativeQuery = true)
    List<UUID> findSelfAndDescendantIds(@Param("id") UUID id, @Param("path") String path);

    /**
     * Breadcrumb ancestors, derived from the path so no recursive query is needed.
     * The path {@code /a/b/c} yields the categories with slugs {@code a} and {@code b}.
     */
    @Query("""
            SELECT c FROM Category c
             WHERE :path LIKE CONCAT(c.path, '/%')
             ORDER BY c.level ASC
            """)
    List<Category> findAncestors(@Param("path") String path);

    long countByParentId(UUID parentId);
}
