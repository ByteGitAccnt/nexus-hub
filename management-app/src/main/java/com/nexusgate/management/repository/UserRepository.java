package com.nexusgate.management.repository;

import com.nexusgate.management.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;
import java.util.Optional;
import java.util.List;

public interface UserRepository extends JpaRepository<User, UUID> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    List<User> findAllByOrganization_IdOrderByCreatedAtAsc(UUID organizationId);
}
