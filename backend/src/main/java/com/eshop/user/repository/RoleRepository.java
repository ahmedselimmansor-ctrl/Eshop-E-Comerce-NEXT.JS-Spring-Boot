package com.eshop.user.repository;

import com.eshop.user.domain.Role;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RoleRepository extends JpaRepository<Role, UUID> {

    @EntityGraph(attributePaths = "permissions")
    Optional<Role> findByName(String name);

    @EntityGraph(attributePaths = "permissions")
    List<Role> findAllByOrderByNameAsc();

    boolean existsByName(String name);

    List<Role> findByNameIn(List<String> names);
}
