package com.eshop.inventory.repository;

import com.eshop.inventory.domain.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {

    Optional<Warehouse> findByCode(String code);

    /** Allocation order: preferred warehouses first. */
    List<Warehouse> findByActiveTrueOrderByPriorityAscCodeAsc();

    boolean existsByCode(String code);
}
