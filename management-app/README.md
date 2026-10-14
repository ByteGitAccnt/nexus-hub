# Nexus management application

The management app is a separate servlet-based Spring Boot application on port `8090`. It is not registered as a gateway route. It uses PostgreSQL and applies versioned schema changes with Flyway; Hibernate is configured to validate the schema rather than modify it.

Set `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` for the management database. The local defaults target a database named `nexushub_management` on localhost.

## API-key service permissions

`api_key_service_permissions.service_slug` is reserved for the normalized slug of a `Service` owned by the same organization as the API key. Phase 1 does not create the `Service` model or expose an endpoint that grants permissions. When that model is introduced, the service layer must resolve the requested slug under the authenticated API key's organization and persist a permission only for a matching registered service; caller-provided identifiers must never be treated as authorization by themselves. A later migration can add a composite foreign key once the Service table and its organization-scoped slug constraint exist.
