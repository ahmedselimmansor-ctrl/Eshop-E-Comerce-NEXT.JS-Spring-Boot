package com.eshop.catalog.repository;

import com.eshop.catalog.domain.Brand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BrandRepository extends JpaRepository<Brand, UUID> {

    Optional<Brand> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Brand> findByActiveTrueOrderByPositionAscNameArAsc();

    List<Brand> findByActiveTrueAndFeaturedTrueOrderByPositionAsc();

    @Query("""
            SELECT b FROM Brand b
             WHERE (:activeOnly = FALSE OR b.active = TRUE)
               AND (CAST(:query AS STRING) IS NULL
                    OR LOWER(b.nameAr) LIKE LOWER(CONCAT('%', CAST(:query AS STRING), '%'))
                    OR LOWER(b.nameEn) LIKE LOWER(CONCAT('%', CAST(:query AS STRING), '%')))
            """)
    Page<Brand> search(@Param("query") String query,
                       @Param("activeOnly") boolean activeOnly,
                       Pageable pageable);
}
