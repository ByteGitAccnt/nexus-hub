package com.nexusgate.management.repository;

import com.nexusgate.management.model.RegisteredService;
import com.nexusgate.management.model.ServiceAuthMode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;

public interface RegisteredServiceRepository extends JpaRepository<RegisteredService, UUID> {

    List<RegisteredService> findAllByOrganizationId(UUID organizationId);

    Optional<RegisteredService> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @org.springframework.data.jpa.repository.Query("""
            select service from RegisteredService service
            where service.id in :serviceIds
              and service.organization.id = :organizationId
              and service.active = true
              and service.authMode = :authMode
            """)
    List<RegisteredService> findEligibleForApiKey(
            @org.springframework.data.repository.query.Param("serviceIds") Collection<UUID> serviceIds,
            @org.springframework.data.repository.query.Param("organizationId") UUID organizationId,
            @org.springframework.data.repository.query.Param("authMode") ServiceAuthMode authMode
    );
}
