package com.nexusgate.management.repository;

import com.nexusgate.management.model.ApiKeyPermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApiKeyPermissionRepository extends JpaRepository<ApiKeyPermission, UUID> {

    List<ApiKeyPermission> findAllByApiKeyIdIn(List<UUID> apiKeyIds);
}
