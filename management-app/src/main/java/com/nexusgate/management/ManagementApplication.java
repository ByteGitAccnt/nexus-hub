package com.nexusgate.management;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(ManagementApplication.class, args);
    }
}

// code0/c-users-vazha-code0-plans-nexushub-api-key-management-lifecy -
// api key life cycle implemented ,
// POST /api/v1/api-keys creates a key;
// GET /api/v1/api-keys lists the caller organization’s keys;
// GET /api/v1/api-keys/{keyId} returns tenant-scoped details;
// PATCH /api/v1/api-keys/{keyId}/status activates or deactivates;
// POST /api/v1/api-keys/{keyId}/revoke permanently revokes.
// All require an active persisted ADMIN and organization. Cross-tenant or
// missing keys return 404.

// code0/c-users-vazha-copilot-session-state-c1b4b9b4-ab37-4e2e-46afe -
// readme.md updated

// code0/c-users-vazha-code0-plans-nexus-hub-management-openapi -
// sswagger and open api configured , links to reach are in readme.md of
// amangment ap

// code0/c-users-vazha-code0-plans-nexus-management-health-actuator
// actuator health exposed

// code0/c-users-vazha-code0-plans-nexus-management-reserve-nexushub-
// /nexushub cannot be owned by other services
// throw validaion error if tried