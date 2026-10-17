package com.nexusgate.management.repository;

import com.nexusgate.management.model.ApiKey;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    List<ApiKey> findAllByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    Optional<ApiKey> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select apiKey from ApiKey apiKey
            where apiKey.id = :id and apiKey.organization.id = :organizationId
            """)
    Optional<ApiKey> findByIdAndOrganizationIdForUpdate(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId
    );

    boolean existsByLookupPrefix(String lookupPrefix);
}
