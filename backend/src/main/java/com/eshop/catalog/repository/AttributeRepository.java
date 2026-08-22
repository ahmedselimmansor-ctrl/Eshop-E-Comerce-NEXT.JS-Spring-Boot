package com.eshop.catalog.repository;

import com.eshop.catalog.domain.Attribute;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttributeRepository extends JpaRepository<Attribute, UUID> {

    @EntityGraph(attributePaths = "values")
    Optional<Attribute> findByCode(String code);

    @EntityGraph(attributePaths = "values")
    Optional<Attribute> findWithValuesById(UUID id);

    boolean existsByCode(String code);

    /** Everything the filter sidebar needs, in one query. */
    @EntityGraph(attributePaths = "values")
    @Query("SELECT a FROM Attribute a WHERE a.filterable = TRUE ORDER BY a.position ASC")
    List<Attribute> findFilterable();

    @EntityGraph(attributePaths = "values")
    @Query("SELECT a FROM Attribute a WHERE a.variantAxis = TRUE ORDER BY a.position ASC")
    List<Attribute> findVariantAxes();

    @EntityGraph(attributePaths = "values")
    @Query("SELECT a FROM Attribute a ORDER BY a.position ASC, a.nameAr ASC")
    List<Attribute> findAllWithValues();

    List<Attribute> findByCodeIn(List<String> codes);
}
