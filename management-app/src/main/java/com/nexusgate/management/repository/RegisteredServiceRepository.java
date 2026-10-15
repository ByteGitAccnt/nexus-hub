package com.nexusgate.management.repository;

import com.nexusgate.management.model.RegisteredService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RegisteredServiceRepository extends JpaRepository<RegisteredService, UUID> {

    List<RegisteredService> findAllByOrganizationId(UUID organizationId);

    Optional<RegisteredService> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
