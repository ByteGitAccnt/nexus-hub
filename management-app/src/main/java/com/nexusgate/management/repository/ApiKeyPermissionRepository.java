package com.nexusgate.management.repository;

import com.nexusgate.management.model.ApiKeyPermission;
import com.nexusgate.management.model.ApiKeyPermissionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ApiKeyPermissionRepository extends JpaRepository<ApiKeyPermission, UUID> {

    List<ApiKeyPermission> findAllByApiKeyIdIn(List<UUID> apiKeyIds);

    @Query("""
        SELECT p.service.id
        FROM ApiKeyPermission p
        WHERE p.apiKey.id = :apiKeyId
          AND p.permission = :permission
    """)
    List<UUID> findServiceIdsByApiKeyIdAndPermission(
            @Param("apiKeyId") UUID apiKeyId,
            @Param("permission") ApiKeyPermissionType permission
    );
}
