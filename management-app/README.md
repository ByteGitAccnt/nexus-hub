# NexusGate Management App

The Management App is the control-plane application in the NexusHub repository. It provides organization registration, user and authentication management, a registered-service catalog, and API-key lifecycle management. This document describes the implementation in this module as it currently exists; for the overall repository and Gateway documentation, see the [root README](../README.md).

> **Integration status:** the Management App and the reactive Gateway are separate applications and are **not yet integrated**. Service records and API-key permissions stored here do not configure Gateway routes or cause the Gateway to validate those API keys.

## Responsibilities and status

The Management App is a Spring MVC application using blocking JPA/PostgreSQL persistence. It is intentionally a separate process from the reactive Gateway, keeping database calls out of the Gateway's event-loop request processing.

| Area                    | Current Management App capability                                                                                                  | Not currently provided by this module                                             |
| ----------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| Organizations and users | Organization registration with an initial ADMIN; ADMIN can create/list organization users and enable or disable MEMBER accounts    | Organization administration endpoints beyond registration and no user deletion    |
| Authentication          | Login, HS256 access JWT issuance, database-backed access-token authentication, refresh-token rotation and logout                   | SSO, MFA, or Gateway-to-management authentication exchange                        |
| Registered services     | ADMIN registration, update, activation/deactivation; ADMIN and MEMBER organization-scoped reads                                    | Gateway route generation, outbound proxying, or SSRF-safe destination enforcement |
| API keys                | ADMIN creation, list/detail, reversible activation, permanent revocation, expiry metadata, and service-scoped `INVOKE` permissions | Gateway API-key verification/enforcement, rate limits, or usage reporting         |
| Persistence             | Flyway-managed PostgreSQL schema                                                                                                   | Usage events, asynchronous processing, daily summaries, or date-range reports     |

## Technology and ports

Versions below come from the Gradle build files and wrapper configuration:

| Component        | Configuration                                                                                             |
| ---------------- | --------------------------------------------------------------------------------------------------------- |
| Java             | Java 21 toolchain                                                                                         |
| Gradle           | Wrapper uses Gradle 9.5.1                                                                                 |
| Spring Boot      | 4.1.0                                                                                                     |
| Web stack        | Spring MVC (`spring-boot-starter-webmvc`)                                                                 |
| Security         | Spring Security; JJWT 0.12.6                                                                              |
| Persistence      | Spring Data JPA, PostgreSQL JDBC driver                                                                   |
| Migrations       | Flyway core and PostgreSQL database support                                                               |
| Password hashing | BCrypt via `BCryptPasswordEncoder`                                                                        |
| Tests            | Spring Boot test support, JUnit Platform, Spring MVC test, Testcontainers PostgreSQL/JUnit Jupiter 1.21.4 |

The Management App listens on **8090** by default. Its public health check is `GET /actuator/health`; only the health Actuator endpoint is exposed. The root Compose file defines PostgreSQL 17, publishing container port 5432 on host `127.0.0.1:5433`. The Gateway defaults to port **8080**. Compose currently defines only PostgreSQL; it does not start the Gateway, Redis, or downstream services.

The Gateway build also uses Java 21 and Spring Boot 4.1.0 and imports Spring Cloud BOM 2025.1.2. The management module does not import a Spring Cloud BOM because it does not use Spring Cloud.

## Directory structure

```text
management-app/
├── build.gradle
├── README.md
└── src/
    ├── main/
    │   ├── java/com/nexusgate/management/
    │   │   ├── ManagementApplication.java
    │   │   ├── config/
    │   │   │   ├── JwtConfiguration.java
    │   │   │   ├── JwtProperties.java
    │   │   │   └── PasswordConfiguration.java
    │   │   ├── controller/
    │   │   │   ├── ApiKeyController.java
    │   │   │   ├── AuthController.java
    │   │   │   ├── OrganizationController.java
    │   │   │   ├── ServiceController.java
    │   │   │   └── UserController.java
    │   │   ├── dto/
    │   │   │   ├── AddUserRequest.java
    │   │   │   ├── ApiKeyCreatedResponse.java
    │   │   │   ├── ApiKeyResponse.java
    │   │   │   ├── ApiKeyServicePermissionResponse.java
    │   │   │   ├── CreateApiKeyRequest.java
    │   │   │   ├── CreateServiceRequest.java
    │   │   │   ├── LoginRequest.java
    │   │   │   ├── LoginResponse.java
    │   │   │   ├── OrganizationRegistrationRequest.java
    │   │   │   ├── OrganizationRegistrationResponse.java
    │   │   │   ├── RefreshTokenRequest.java
    │   │   │   ├── RegisteredServiceResponse.java
    │   │   │   ├── UpdateApiKeyStatusRequest.java
    │   │   │   ├── UpdateServiceStatusRequest.java
    │   │   │   ├── UpdateUserStatusRequest.java
    │   │   │   └── UserResponse.java
    │   │   ├── exception/
    │   │   │   ├── ApiExceptionHandler.java
    │   │   │   ├── InvalidCredentialsException.java
    │   │   │   ├── InvalidRefreshTokenException.java
    │   │   │   ├── InvalidRequestException.java
    │   │   │   ├── ResourceConflictException.java
    │   │   │   └── ResourceNotFoundException.java
    │   │   ├── model/
    │   │   │   ├── ApiKey.java
    │   │   │   ├── ApiKeyPermission.java
    │   │   │   ├── ApiKeyPermissionType.java
    │   │   │   ├── ApiKeyStatus.java
    │   │   │   ├── Organization.java
    │   │   │   ├── OrganizationStatus.java
    │   │   │   ├── RefreshToken.java
    │   │   │   ├── RegisteredService.java
    │   │   │   ├── ServiceAuthMode.java
    │   │   │   ├── User.java
    │   │   │   ├── UserRole.java
    │   │   │   └── UserStatus.java
    │   │   ├── repository/
    │   │   │   ├── ApiKeyPermissionRepository.java
    │   │   │   ├── ApiKeyRepository.java
    │   │   │   ├── OrganizationRepository.java
    │   │   │   ├── RefreshTokenRepository.java
    │   │   │   ├── RegisteredServiceRepository.java
    │   │   │   └── UserRepository.java
    │   │   ├── security/
    │   │   │   ├── AuthenticatedUserPrincipal.java
    │   │   │   ├── JwtAuthenticationFilter.java
    │   │   │   └── ManagementSecurityConfiguration.java
    │   │   └── service/
    │   │       ├── AccessTokenIssuer.java
    │   │       ├── ApiKeyManagementService.java
    │   │       ├── AuthenticationService.java
    │   │       ├── JwtPrincipalLoader.java
    │   │       ├── OrganizationRegistrationService.java
    │   │       └── ServiceRegistrationService.java
    │   └── resources/
    │       ├── application.yaml
    │       └── db/migration/
    │           ├── V1__create_management_schema.sql
    │           ├── V2__add_user_name.sql
    │           ├── V3__create_registered_services.sql
    │           ├── V4__api_key_lifecycle_and_service_permissions.sql
    │           └── V5__reserve_nexushub_service_path.sql
    └── test/java/com/nexusgate/management/
        ├── ApiKeyManagementIntegrationTest.java
        ├── AuthenticationIntegrationTest.java
        ├── OrganizationRegistrationIntegrationTest.java
        ├── RegisteredServicePersistenceIntegrationTest.java
        └── ServiceRegistrationIntegrationTest.java
```

### Main classes and layer responsibilities

- `ManagementApplication.java` starts this independently deployable Spring Boot application.
- `config/ JwtConfiguration.java` creates the HMAC signing key from `JwtProperties`; `JwtProperties.java` binds and validates JWT settings, including requiring a non-empty secret of at least 32 UTF-8 bytes and positive token lifetimes. `PasswordConfiguration.java` provides the BCrypt `PasswordEncoder`.
- `controller/` exposes the HTTP routes and binds validated request DTOs. `OrganizationController`, `AuthController`, `UserController`, `ServiceController`, and `ApiKeyController` delegate to application services rather than accessing repositories directly.
- `dto/` defines the request and response contracts. Responses deliberately use explicit records instead of serializing JPA entities, so password hashes, refresh-token hashes, API-key hashes, and other persistence details are not exposed.
- `exception/ApiExceptionHandler.java` maps known application exceptions and bean-validation failures to RFC-style `ProblemDetail` responses. `ResourceConflictException`, `ResourceNotFoundException`, `InvalidRequestException`, `InvalidCredentialsException`, and `InvalidRefreshTokenException` represent handled conditions.
- `model/` contains JPA entities and state enums: `Organization`, `User`, `RefreshToken`, `RegisteredService`, `ApiKey`, and `ApiKeyPermission`. `ServiceAuthMode` is `PUBLIC`, `JWT`, or `API_KEY`; API-key status is `ACTIVE`, `INACTIVE`, or `REVOKED`; the supported service permission type is `INVOKE`.
- `repository/` contains Spring Data JPA persistence interfaces. Tenant-specific methods scope lookups by organization; `RefreshTokenRepository` also locks a token during refresh and bulk-revokes active refresh tokens at logout; `ApiKeyRepository` locks keys for state changes.
- `security/ManagementSecurityConfiguration.java` configures stateless authentication, role restrictions, and the JWT filter. `JwtAuthenticationFilter.java` validates signed access tokens before creating an `AuthenticatedUserPrincipal`; `JwtPrincipalLoader.java` reloads the user and organization from PostgreSQL and rejects disabled/inactive identities. The principal carries the persisted user ID, organization ID, and role.
- `service/` implements business rules and transaction boundaries. `OrganizationRegistrationService` handles organization and user operations; `AuthenticationService` handles login, refresh rotation, and logout; `AccessTokenIssuer` creates access JWTs; `ServiceRegistrationService` validates persisted ADMIN identity, tenant-scopes service operations and maps safe responses; `ApiKeyManagementService` validates service eligibility, generates and hashes secrets, persists permissions transactionally, and enforces key lifecycle rules.
- `application.yaml` defines the app name, `.env` import, datasource defaults, Hibernate schema-validation mode, Flyway location, port, and JWT settings.

### Test classes

All five management test classes use PostgreSQL Testcontainers and Spring integration testing:

| Test class                                    | Verified behavior represented in the tests                                                                                                                                                                                                                         |
| --------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `OrganizationRegistrationIntegrationTest`     | Organization/initial ADMIN creation, normalized uniqueness conflicts, invalid registration input, ignoring forged role/tenant fields, transaction rollback, member creation/password hashing, and persisted ADMIN checks                                           |
| `AuthenticationIntegrationTest`               | Login/JWT claims and refresh-token hashing, uniform bad-credential responses, identity sourced from persisted data, user listing/creation/status authorization and tenant isolation, refresh rotation/expiry/concurrency, and logout revocation                    |
| `RegisteredServicePersistenceIntegrationTest` | Entity/schema persistence, organization-scoped name uniqueness, foreign-key/auth-mode/reserved-path constraints, nullable target path, and database active default |
| `ServiceRegistrationIntegrationTest`          | Service creation/read/update/status endpoints, validation and duplicate handling, ADMIN/MEMBER access, empty lists, tenant isolation, URL syntax checks, reserved-path rejection, and update timestamps |
| `ApiKeyManagementIntegrationTest`             | Key generation/hash/prefix and one-time secret response, service eligibility and tenant checks, role/auth restrictions, list/detail secrecy, activation/expiry/revocation transitions, legacy migration data preservation, constraints, and transactional rollback |

The tests start isolated PostgreSQL containers; Docker (or a compatible Testcontainers runtime) must be available. They do not require the developer's Compose database.

## Architecture and request lifecycle

For a protected management request, the usual flow is:

```text
HTTP request
  -> Spring Security filter chain
  -> JwtAuthenticationFilter (when a Bearer token is supplied)
  -> JwtPrincipalLoader (validate current persisted user and organization)
  -> Controller (bind request DTO and run bean validation)
  -> Service (authorization, tenant rules, transaction)
  -> Repository (organization-scoped query or mutation)
  -> PostgreSQL
  -> explicit response DTO
```

Public registration, login, and refresh requests do not require an access JWT. A protected request with a valid token is still authorized against the database: the filter reloads the user and verifies that the user is active, the organization is active, and its ID matches the token's `organizationid` claim. Authorities are created using the persisted role, not a caller-supplied request field or untrusted role claim.

Controllers bind DTOs and delegate. Services own business and tenant rules and use Spring `@Transactional` boundaries; repositories encapsulate database access. This keeps authorization and state changes consistently testable and avoids coupling HTTP handling directly to persistence. Listing services uses the principal's verified organization. ADMIN mutations additionally reload the actor and check ADMIN role, active user status, and active organization. Key and service detail/mutation queries include the caller's organization; a foreign-tenant identifier therefore follows the same not-found path as a nonexistent identifier.

Request DTO constraints are evaluated at the controller boundary with `@Valid`. Some domain checks, such as service eligibility for API-key assignment or expiry at the moment of reactivation, are enforced in the service. The exception advice maps known conflicts to 409, missing scoped resources to 404, invalid requests and DTO validation to 400, and invalid credentials to 401. Spring Security's entry point and access-denied handler set 401 and 403 respectively; those filter-level responses are status-only.

### Authentication and tokens

`AuthenticationService.login` normalizes the email, verifies the BCrypt password, and requires an active user and organization. `AccessTokenIssuer` issues an HS256 JWT containing issuer, audience, `userid`, `organizationid`, `role`, `iat`, and `exp`. Default access-token lifetime is 15 minutes. The Management App also creates a cryptographically random opaque refresh token, stores its SHA-256 hash, and defaults its lifetime to 30 days.

Refresh locks and validates the old token, checks current user/organization state, creates a replacement, then revokes and links the old record to the replacement in one transaction. Logout requires an access JWT and revokes all active, unexpired refresh tokens for that user; existing access JWTs remain valid until their expiry. The Gateway does not call the Management App to introspect a token.

### Services and API-key permissions

`ServiceRegistrationService` persists service configuration under the administrator's persisted organization. `targetUrl` is checked for absolute HTTP(S) URI syntax, a host, and absence of user-info/fragment; no DNS resolution or outbound request is made. The `PUBLIC`, `JWT`, and `API_KEY` values are stored metadata; they do not currently alter Gateway behavior.

The `/nexushub` path prefix is reserved for the Gateway's static Management App route. Service creation and updates reject `/nexushub` and paths beneath it, case-insensitively; a distinct prefix such as `/nexushub-orders` remains valid. Flyway V5 also enforces this rule in PostgreSQL, so direct database writes cannot claim the reserved route. Existing rows that conflict with the constraint must be reviewed before applying V5; the migration does not rewrite service paths.

`ApiKeyManagementService` requires at least one requested service. Every service must exist in the admin's organization, be active, and have `auth_mode = API_KEY`. It generates a 32-byte random secret with an `ng_live_` prefix, stores its SHA-256 hash and a separate non-authenticating lookup prefix, and returns the full secret only in the creation response. The `INVOKE` permission links the API key and registered service by UUID. Database composite foreign keys reinforce that both records belong to the same organization. Those permissions are management data only until Gateway integration implements verification.

## Management App and Gateway separation

### Current Management App functionality

The Management App stores organizational and service-control-plane records in PostgreSQL. Its authenticated endpoints implement management operations listed in the API reference below. It does not currently publish a service-configuration feed to the Gateway.

### Current Gateway functionality

The root application is a reactive Spring Cloud Gateway/WebFlux app, configured from the repository-root `nexus.yml` by `NexusConfigLoader` and `NexusRouteBuilder`. It currently:

- Builds routes from the `services` entries in `nexus.yml`, not from `registered_services`.
- Applies its JWT gateway filter to configured routes, validating a signature plus configured issuer/audience and extracting the configured identity claim (the sample config uses `userid`).
- Implements Redis-backed token-bucket rate limiting with an outer client-IP limiter and an inner authenticated-identity limiter. The configured Redis failure strategy controls fail-open versus fail-closed behavior.
- Logs configured request metadata and sets/propagates `X-Request-ID` (accepting an incoming request ID when supplied).
- Includes Actuator, Micrometer Prometheus registry dependencies and monitoring-related configuration/metrics instrumentation.
- Can build configured downstream management routes and rewrite their paths based on `nexus.yml`.

These features run independently of management data. In particular, current Gateway JWT filtering does not query the Management App to check whether a user or organization was subsequently disabled.

### Integration status and known gaps

The applications are **not connected end-to-end**. The Management App's registered-service changes do not add, update, or remove Gateway routes. API keys and their `INVOKE` permissions are not checked by a Gateway filter. The Management App's per-service metadata, tenant identities, and API-key eligibility rules are not yet enforced during proxied requests. The Gateway's YAML configuration and the Management App database are independent configuration sources.

There is also a default JWT configuration mismatch: the Management App defaults to issuer `http://localhost:8090` and audience `nexushub`, while the checked-in Gateway `nexus.yml` specifies issuer `http://localhost:8081` and audience `nexusgate`. Both use HS256 and the `userid` identity-claim convention in their current configuration, but they will not accept each other's tokens unless the secret, issuer, and audience are deliberately configured to match. Matching settings alone does not constitute a complete integrated authorization design.

### Planned integration and documentation updates

Future work may define how the Gateway obtains approved service routes, verifies API-key secrets, enforces organization/service permissions, and applies tenant-specific rate limits. The transport, synchronization model, failure behavior, and authorization contract have not been decided here. Update this section when that design and implementation are reviewed; until then, do not treat stored management data as Gateway-enforced policy.

## Configuration and environment

Management settings are in `src/main/resources/application.yaml`. Spring Boot does **not** automatically load arbitrary `.env` files; this app explicitly imports `optional:file:../.env[.properties]`, relative to the module's working directory. When launched with the Gradle wrapper from the repository root, `bootRun` uses the management module working directory and imports the root `.env`.

| Variable                | Purpose                                                           | Required/default                                                                                           |
| ----------------------- | ----------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `DATABASE_URL`          | Full JDBC URL for Management App database                         | Optional override; default is composed from PostgreSQL variables as `jdbc:postgresql://<host>:<port>/<db>` |
| `DATABASE_USERNAME`     | JDBC user override                                                | Optional; falls back to `POSTGRES_USER`, then `nexusgate_app`                                              |
| `DATABASE_PASSWORD`     | JDBC password override                                            | Optional override; falls back to `POSTGRES_PASSWORD`; the effective value must authenticate to PostgreSQL  |
| `POSTGRES_HOST`         | Database host in composed JDBC default                            | Optional; `localhost`                                                                                      |
| `POSTGRES_PORT`         | Database host port in composed JDBC default                       | Optional; `5433`                                                                                           |
| `POSTGRES_DB`           | Database name in composed JDBC default and Compose initialization | Optional; `nexusgate`                                                                                      |
| `POSTGRES_USER`         | Database user in composed JDBC default and Compose initialization | Optional; `nexusgate_app`                                                                                  |
| `POSTGRES_PASSWORD`     | Compose initialization password and JDBC fallback                 | Set a local/deployment secret; `.env.example` contains a placeholder only                                  |
| `JWT_SECRET`            | Shared HMAC signing/verification secret for management JWTs       | **Required**; at least 32 UTF-8 bytes. Keep secret and out of source control                               |
| `JWT_ISSUER`            | Issuer claim and accepted issuer                                  | Optional; `http://localhost:8090`                                                                          |
| `JWT_AUDIENCE`          | Audience claim and required audience                              | Optional; `nexushub`                                                                                       |
| `JWT_ACCESS_TOKEN_TTL`  | Access JWT lifetime as a Spring `Duration`                        | Optional; `PT15M`                                                                                          |
| `JWT_REFRESH_TOKEN_TTL` | Refresh token lifetime as a Spring `Duration`                     | Optional; `P30D`                                                                                           |
| `REDIS_HOST`            | Gateway Redis host resolved from `nexus.yml`                      | Required by the Gateway configuration when its placeholder is resolved                                     |
| `REDIS_PORT`            | Gateway Redis port resolved from `nexus.yml`                      | Required by the Gateway configuration when its placeholder is resolved                                     |

The root `.env.example` currently documents PostgreSQL initialization variables and optional database overrides. Copy it to the ignored root `.env`, replace its password placeholder with a local secret, and add a `JWT_SECRET` with at least 32 UTF-8 bytes for the Management App. Do not commit `.env` or real credentials. `JWT_SECRET` must be provided to the Gateway process environment as well: the Gateway's `EnvironmentVariableResolver` reads operating-system environment variables directly while parsing `nexus.yml`; it does not use the Management App's `.env` import.

If sharing JWT verification is intentionally configured, set identical `JWT_SECRET`, issuer, and audience values for both applications, use HS256, and retain the Gateway's configured `userid` identity claim. The current checked-in issuer/audience defaults differ as described above. The Management App also includes `organizationid` and `role` claims; its own authorization reloads their authoritative values from the database.

Database passwords and JWT signing secrets must be stored in a secret manager or local ignored environment file, not in checked-in YAML, examples, logs, requests, or documentation. API-key secrets and refresh tokens are credentials too and must not be logged or committed.

## PostgreSQL and Flyway

The Management App uses PostgreSQL with Hibernate `ddl-auto: validate`; Hibernate checks entity/schema compatibility and does not generate schema changes. Flyway runs the SQL migrations from `classpath:db/migration` at startup in version order:

| Migration                                           | Schema changes                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `V1__create_management_schema.sql`                  | Creates `organizations`, `users`, `api_keys`, the original slug-based `api_key_service_permissions`, and `refresh_tokens`. Defines UUID primary keys, normalized organization slug/email checks, role/status checks, unique email/slug/key hash/lookup prefix/token hash constraints, organization indexes, refresh-token user/organization composite ownership FK, permission uniqueness, and expiry/hash checks.                                                                   |
| `V2__add_user_name.sql`                             | Adds `users.name` with a temporary empty-string default for existing rows, then removes the default.                                                                                                                                                                                                                                                                                                                                                                                 |
| `V3__create_registered_services.sql`                | Creates `registered_services` with organization FK, organization/name uniqueness, active and timestamp defaults, supported auth-mode check, and organization lookup index.                                                                                                                                                                                                                                                                                                           |
| `V4__api_key_lifecycle_and_service_permissions.sql` | Adds API-key status with legacy-row backfill (`revoked_at` determines `REVOKED`), status/revocation consistency checks, and composite `(id, organization_id)` unique keys. Renames the old slug permission table to `api_key_service_permissions_legacy` without deleting its rows. Creates the new UUID-based permission table with key+organization and service+organization foreign keys, unique key/service/permission tuples, `INVOKE` check, and service/organization indexes. |

The legacy `service_slug` values have no safe automatic mapping to service UUIDs (the registered-service model has no slug); they are retained for review, not used as new UUID permissions.

### Safe local database setup

Compose uses an **external named volume** called `nexusgate_postgres_data`, an existing container name `nexusgate-postgres`, and host port 5433. Before starting it, inspect whether the container and volume already exist and whether they are the expected local database. Do not remove or recreate an existing container or volume to resolve a conflict.

From the repository root in PowerShell. If `.env` already exists, edit it in place rather than overwriting local settings:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
# Edit .env locally: replace the database password placeholder and add JWT_SECRET=<at-least-32-UTF-8-bytes>
docker ps -a --filter "name=nexusgate-postgres"
docker volume inspect nexusgate_postgres_data
docker compose up -d postgres
docker compose ps
```

If the existing container or external volume cannot be safely reused, stop and resolve that environment conflict rather than deleting data. Compose starts only PostgreSQL; when the Management App starts, Flyway may apply unapplied migrations to the configured database. Back up important data and inspect the migration state before pointing the app at a non-disposable database. Do not edit already-applied migrations.

## API reference

All routes below are served by the Management App at `http://localhost:8090` by default. JSON request examples use placeholder values. Protected requests use:

```http
Authorization: Bearer <access-jwt>
Content-Type: application/json
```

The `organizationId` fields shown in responses are informational. Management mutation requests do not accept an organization ID as an authorization selector; tenant identity is derived from the authenticated persisted principal. Enum values are case-sensitive.

### Organization and authentication

#### `POST /api/v1/organizations/register`

Public organization and initial ADMIN registration. Success: `201 Created`.

Request fields: `organizationName` (required, max 150), `organizationSlug` (required, max 63; alphanumeric/hyphen format), `adminName` (required, max 150), `adminEmail` (required valid email, max 320), `adminPassword` (required, 12–72 characters).

```json
{
  "organizationName": "Acme Systems",
  "organizationSlug": "acme-systems",
  "adminName": "Ada Admin",
  "adminEmail": "ada@example.com",
  "adminPassword": "<strong-password>"
}
```

Response:

```json
{
  "id": "<organization-uuid>",
  "name": "Acme Systems",
  "slug": "acme-systems",
  "status": "ACTIVE",
  "createdAt": "2026-10-17T09:00:00Z",
  "admin": {
    "id": "<admin-user-uuid>",
    "name": "Ada Admin",
    "email": "ada@example.com",
    "role": "ADMIN",
    "status": "ACTIVE",
    "createdAt": "2026-10-17T09:00:00Z"
  }
}
```

Invalid fields return 400; an already-used slug or email returns 409.

#### `POST /api/v1/auth/login`

Public login. Success: `200 OK`.

Request: `email` (valid email, max 320) and `password` (nonblank, max 72).

```json
{ "email": "ada@example.com", "password": "<password>" }
```

Response:

```json
{
  "accessToken": "<access-jwt>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshToken": "<opaque-refresh-token>",
  "user": {
    "id": "<user-uuid>",
    "organizationId": "<organization-uuid>",
    "role": "ADMIN"
  }
}
```

Unknown email, incorrect password, disabled user, or inactive organization returns 401 with the generic invalid-credentials problem detail. Validation failures return 400.

#### `POST /api/v1/auth/refresh`

Public refresh-token rotation. Success: `200 OK`; the old refresh token is revoked and a replacement is returned.

Request: nonblank `refreshToken`.

```json
{ "refreshToken": "<current-refresh-token>" }
```

Response has the same shape as login, with a new access JWT and refresh token. Invalid, expired, revoked, or inactive-account refresh credentials return 401; invalid request fields return 400.

#### `POST /api/v1/auth/logout`

Requires a valid access JWT. Revokes the caller's active refresh tokens. Success: `204 No Content`; no request body and no response body.

```http
POST /api/v1/auth/logout
Authorization: Bearer <access-jwt>
```

Missing/invalid authentication returns 401. Logout does not revoke already-issued access JWTs; they expire according to their `exp`.

### User management

All three user endpoints require ADMIN authorization. User creation always creates a MEMBER within the authenticated admin's organization.

#### `POST /api/v1/users`

Creates a member. Success: `201 Created`.

Request: `name` (required, max 150), `email` (valid email, max 320), `password` (12–72 characters).

```json
{
  "name": "Morgan Member",
  "email": "morgan@example.com",
  "password": "<strong-password>"
}
```

Response:

```json
{
  "id": "<user-uuid>",
  "organizationId": "<organization-uuid>",
  "name": "Morgan Member",
  "email": "morgan@example.com",
  "role": "MEMBER",
  "status": "ACTIVE",
  "createdAt": "2026-10-17T09:10:00Z"
}
```

Invalid input returns 400; an email already registered returns 409; a non-ADMIN receives 403 and a missing/invalid token receives 401.

#### `GET /api/v1/users`

Lists all users (including the organization's ADMIN) in the authenticated ADMIN's organization. No query parameters or request body. Success: `200 OK`.

Response is an array of `UserResponse` objects; for example:

```json
[
  {
    "id": "<admin-user-uuid>",
    "organizationId": "<organization-uuid>",
    "name": "Ada Admin",
    "email": "ada@example.com",
    "role": "ADMIN",
    "status": "ACTIVE",
    "createdAt": "2026-10-17T09:00:00Z"
  },
  {
    "id": "<member-user-uuid>",
    "organizationId": "<organization-uuid>",
    "name": "Morgan Member",
    "email": "morgan@example.com",
    "role": "MEMBER",
    "status": "ACTIVE",
    "createdAt": "2026-10-17T09:10:00Z"
  }
]
```

An empty result is `[]`. Passwords and password hashes are not included. MEMBER receives 403; unauthenticated caller receives 401.

#### `PUT /api/v1/users/{userId}/status`

Enables/disables a MEMBER in the caller's organization. Success: `200 OK`. Request requires non-null boolean `active`.

```json
{ "active": false }
```

Response is the `UserResponse` shape above, with `status` set to `DISABLED` or `ACTIVE`. Invalid/missing `active` returns 400. A non-member, nonexistent user, or user from another organization returns 404 (`Member not found`); a non-ADMIN receives 403.

### Registered services

Service reads require ADMIN or MEMBER; creation, update, and status changes require ADMIN. `CreateServiceRequest` is used for both creation and full update: fields are `name` (required, max 100), `targetUrl` (required, max 2048; absolute HTTP(S), host required, no user-info or fragment), `basePath` (required, max 255; one absolute path prefix, not `//`, query, or fragment), optional `targetPath` (max 2048; null/blank clears it), and `authMode` (required: `PUBLIC`, `JWT`, or `API_KEY`). URL validation is syntax-only and is not SSRF protection.

The common service response shape is:

```json
{
  "id": "<service-uuid>",
  "organizationId": "<organization-uuid>",
  "name": "Orders",
  "targetUrl": "https://orders.example.com/api",
  "basePath": "/orders",
  "targetPath": "/api",
  "active": true,
  "authMode": "API_KEY",
  "createdAt": "2026-10-17T09:20:00Z",
  "updatedAt": "2026-10-17T09:20:00Z"
}
```

`basePath` is the public prefix recorded for a service, and optional `targetPath` records a path prefix for rewriting. This module stores and returns those values; it does not yet drive Gateway rewriting.

#### `POST /api/v1/services`

ADMIN registers a service. Success: `201 Created`.

```json
{
  "name": "Orders",
  "targetUrl": "https://orders.example.com/api",
  "basePath": "/orders",
  "targetPath": "/api",
  "authMode": "API_KEY"
}
```

Response: common service response above. Invalid fields/URL syntax return 400; duplicate name within the same organization returns 409; missing authentication returns 401; non-ADMIN returns 403.

#### `GET /api/v1/services`

ADMIN or MEMBER lists services in their own organization. No body. Success: `200 OK`; response is an array of common service responses, for example:

```json
[
  {
    "id": "<service-uuid>",
    "organizationId": "<organization-uuid>",
    "name": "Orders",
    "targetUrl": "https://orders.example.com/api",
    "basePath": "/orders",
    "targetPath": "/api",
    "active": true,
    "authMode": "API_KEY",
    "createdAt": "2026-10-17T09:20:00Z",
    "updatedAt": "2026-10-17T09:20:00Z"
  }
]
```

An empty result is `[]`.

#### `GET /api/v1/services/{serviceId}`

ADMIN or MEMBER reads one service in their own organization. No body. Success: `200 OK` with one common service response. Nonexistent and cross-tenant IDs return 404.

#### `PUT /api/v1/services/{serviceId}`

ADMIN performs a **full configuration update**, not a partial patch. Resupply all required fields from `CreateServiceRequest`; `targetPath` may be omitted or null to clear it. Success: `200 OK`.

```json
{
  "name": "Orders v2",
  "targetUrl": "https://orders.example.com/v2",
  "basePath": "/orders",
  "targetPath": "/v2",
  "authMode": "JWT"
}
```

Response: updated common service response. Invalid input/URL returns 400; duplicate organization-scoped name returns 409; missing or cross-tenant ID returns 404; MEMBER receives 403.

#### `PATCH /api/v1/services/{serviceId}/status`

ADMIN activates/deactivates without deleting the record. Request requires non-null `active` boolean:

```json
{ "active": false }
```

Success: `200 OK` with the common service response reflecting the new `active` value and `updatedAt`. Invalid/missing boolean returns 400; missing or cross-tenant service returns 404; MEMBER receives 403. Repeated status changes are supported.

### API-key management

All API-key management endpoints require an active persisted ADMIN and active organization. Creation requires at least one distinct service ID; selected services must be active, belong to the caller's organization, and use `API_KEY` authentication mode. The only current permission is `INVOKE`.

The safe metadata response is:

```json
{
  "id": "<api-key-uuid>",
  "organizationId": "<organization-uuid>",
  "name": "Mobile App Key",
  "lookupPrefix": "ng_live_<non-secret-prefix>",
  "status": "ACTIVE",
  "createdAt": "2026-10-17T09:30:00Z",
  "expiresAt": "2027-01-01T00:00:00Z",
  "revokedAt": null,
  "permissions": [
    {
      "serviceId": "<service-uuid>",
      "serviceName": "Orders",
      "permission": "INVOKE"
    }
  ]
}
```

The raw secret and `keyHash` are never part of ordinary metadata.

#### `POST /api/v1/api-keys`

Creates an API key. Success: `201 Created`. Request fields: `name` (required, max 100), `serviceIds` (required, non-empty, max 100, UUID values, no duplicates), optional `expiresAt` (future ISO-8601 instant).

```json
{
  "name": "Mobile App Key",
  "serviceIds": ["<service-uuid>"],
  "expiresAt": "2027-01-01T00:00:00Z"
}
```

Response contains the secret once and nested safe metadata:

```json
{
  "secret": "ng_live_<one-time-secret>",
  "apiKey": {
    "id": "<api-key-uuid>",
    "organizationId": "<organization-uuid>",
    "name": "Mobile App Key",
    "lookupPrefix": "ng_live_<non-secret-prefix>",
    "status": "ACTIVE",
    "createdAt": "2026-10-17T09:30:00Z",
    "expiresAt": "2027-01-01T00:00:00Z",
    "revokedAt": null,
    "permissions": [
      {
        "serviceId": "<service-uuid>",
        "serviceName": "Orders",
        "permission": "INVOKE"
      }
    ]
  }
}
```

Invalid request or ineligible/foreign service IDs return 400. Store the raw secret securely when shown; it cannot be retrieved again. Duplicate/nonexistent service IDs are rejected. A failed transaction does not leave a key without its permission rows.

#### `GET /api/v1/api-keys`

Lists the caller organization's keys. No body. Success: `200 OK`; returns an array of safe metadata objects, for example:

```json
[
  {
    "id": "<api-key-uuid>",
    "organizationId": "<organization-uuid>",
    "name": "Mobile App Key",
    "lookupPrefix": "ng_live_<non-secret-prefix>",
    "status": "ACTIVE",
    "createdAt": "2026-10-17T09:30:00Z",
    "expiresAt": "2027-01-01T00:00:00Z",
    "revokedAt": null,
    "permissions": [
      {
        "serviceId": "<service-uuid>",
        "serviceName": "Orders",
        "permission": "INVOKE"
      }
    ]
  }
]
```

The response never contains the raw secret or hash. Empty list returns `[]`.

#### `GET /api/v1/api-keys/{keyId}`

Reads safe metadata for a key in the caller's organization. No body. Success: `200 OK`. Missing and cross-tenant IDs return 404.

#### `PATCH /api/v1/api-keys/{keyId}/status`

Reversibly disables or re-enables an eligible key. Request:

```json
{ "active": false }
```

Success: `200 OK` with safe metadata; false results in `INACTIVE`, true in `ACTIVE`. An expired key cannot be reactivated; a revoked key cannot be reactivated. These prohibited transitions return 409. Missing/cross-tenant IDs return 404.

#### `POST /api/v1/api-keys/{keyId}/revoke`

Permanently revokes a key. No request body. Success: `200 OK` with safe metadata showing `status: "REVOKED"` and `revokedAt` populated. Repeated revocation is safe; no endpoint can reactivate a revoked key. Missing/cross-tenant IDs return 404.

## Swagger UI and OpenAPI

The Management App provides interactive Swagger UI and an OpenAPI 3 JSON specification for its current endpoints:

- Swagger UI: <http://localhost:8090/swagger-ui/index.html>
- OpenAPI JSON: <http://localhost:8090/v3/api-docs>

Start the Management App using the [local setup instructions](#local-setup-and-run); PostgreSQL and the required `JWT_SECRET` must be configured. Swagger UI's **Authorize** button accepts an access JWT; paste the token itself (without `Bearer `), and Swagger sends it as `Authorization: Bearer <accessToken>`. Organization registration, login, and refresh are public. Logout and management endpoints require a bearer JWT; user and API-key management and service writes require ADMIN, while service reads permit ADMIN or MEMBER. The generated specification covers the Management App only and does not indicate that the Gateway consumes its service records, keys, or authorization rules. DTO constraints drive generated schemas, but examples and error details may be less exhaustive than this README.

The Actuator health endpoint is available at <http://localhost:8090/actuator/health> without a JWT, for local health checks. Other Actuator endpoints are not exposed.

## Security and tenant isolation

- Public routes are organization registration, login, and refresh. Logout and all user, service, and API-key routes require authentication; roles are applied per route as described above.
- The Management App derives identity from the verified JWT's `userid` and `organizationid` only to locate and check the principal. `JwtPrincipalLoader` verifies the current persisted user and active organization; service code rechecks ADMIN role and status for administrative mutations. Do not let clients choose the tenant with an organization ID in a request.
- Service and key reads/mutations are scoped by organization. Missing and cross-tenant IDs intentionally use the same 404 behavior. User status updates likewise target only a same-organization MEMBER.
- Passwords are hashed with BCrypt. Refresh tokens and API keys are generated with secure random bytes and only SHA-256 hashes are persisted. API-key lookup prefixes are identifiers, not credentials; the full secret must still match its stored hash in a future validator.
- API-key states are `ACTIVE`, `INACTIVE`, and permanently `REVOKED`. Expiration is separate: an expired `ACTIVE` key is not usable and cannot be reactivated. Revoke populates `revoked_at`; migration V4 preserves revoked records as revoked.
- Never log or expose raw API keys, refresh tokens, passwords, password hashes, or token hashes. API-key raw secret appears only in the creation response; refresh raw tokens appear in login/refresh responses.
- **SSRF limitation:** registered-service URL validation checks URI syntax only. It does not resolve DNS, restrict private/link-local/loopback addresses, protect against DNS rebinding, or make outbound requests. Do not use stored target URLs for Gateway forwarding until a separate robust destination validation/SSRF policy is designed and enforced.
- The Gateway currently verifies JWT signature/issuer/audience and extracts an identity claim, but does not consult management persistence for user/organization status or role. JWT signing configuration must be carefully aligned before relying on shared tokens.
- No API-key Gateway authentication, service permission enforcement, user-based organization validation at the Gateway, or management-to-Gateway configuration synchronization is implemented yet.

## Local setup and run

### Prerequisites

Install a Java 21 JDK (the Gradle toolchain uses Java 21), Docker with Compose support (for PostgreSQL and Testcontainers), and use the checked-in Gradle wrapper. The wrapper downloads Gradle 9.5.1 if needed; no separate Gradle installation is required.

### Start PostgreSQL and configure credentials

From the repository root, follow the [safe database setup](#safe-local-database-setup): check the existing `nexusgate-postgres` container and external `nexusgate_postgres_data` volume, configure the ignored root `.env`, then run:

```powershell
docker compose up -d postgres
docker compose ps
```

Do not remove volumes or containers to work around an existing-resource conflict. Compose publishes PostgreSQL on `127.0.0.1:5433`. Add `JWT_SECRET=<at-least-32-UTF-8-bytes>` to the local root `.env` or provide it as an environment variable before starting the app.

### Run from repository root

```powershell
.\gradlew.bat :management-app:bootRun
```

This runs the management module's Spring Boot application, which connects to the configured PostgreSQL instance and applies pending Flyway migrations. It does not start the Gateway. Gradle's `bootRun` task sets the JVM timezone to UTC.

### Run from the module directory

```powershell
Set-Location management-app
..\gradlew.bat bootRun
```

The module configuration imports `../.env` relative to this working directory.

On successful startup, the Management App listens on `http://localhost:8090`. Check startup logs for datasource/Flyway completion. To verify the API, register an organization, then log in with its initial admin:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8090/api/v1/organizations/register `
  -ContentType "application/json" `
  -Body '{"organizationName":"Example Org","organizationSlug":"example-org","adminName":"Admin","adminEmail":"admin@example.com","adminPassword":"<strong-password>"}'
```

Stop the application with `Ctrl+C`. To stop only the Compose PostgreSQL service without deleting its data:

```powershell
docker compose stop postgres
```

Starting the app can migrate the configured database. Point it at the intended database only after checking it and its Flyway state.

## End-to-end management workflow

The following sequence illustrates management operations only. Replace placeholders; do not paste real credentials into source control or logs.

1. Register an organization using `POST /api/v1/organizations/register`; save the returned admin identity.
2. Log in using `POST /api/v1/auth/login`; hold the returned access JWT and refresh token securely.
3. Create an organization member with `POST /api/v1/users` and the admin Bearer JWT.
4. Register a service using `POST /api/v1/services` with `"authMode":"API_KEY"` and a syntactically valid HTTPS `targetUrl`.
5. List services with `GET /api/v1/services` or inspect the service UUID with `GET /api/v1/services/{serviceId}`.
6. Create a key with `POST /api/v1/api-keys`, including the eligible service UUID and optional future `expiresAt`.
7. Copy the returned `secret` into an appropriate secret store. It is returned once; the database stores only its hash.
8. Use `GET /api/v1/api-keys` or `GET /api/v1/api-keys/{keyId}` to inspect safe metadata and permissions. The secret is not returned again.
9. Use `PATCH /api/v1/api-keys/{keyId}/status` with `{"active":false}` to deactivate, then `{"active":true}` to reactivate while the key is not expired or revoked.
10. Use `POST /api/v1/api-keys/{keyId}/revoke` for permanent revocation.

These operations manage records, not proxied traffic. At present the Gateway will not validate the created key, check its service permissions, or automatically see the service registration. Likewise, expiry/inactive/revoked states are enforced by the Management App's state transitions and metadata but are not yet evaluated by the Gateway.

## Tests and build

Run these commands from the repository root on Windows:

```powershell
.\gradlew.bat :management-app:test
.\gradlew.bat :management-app:build
```

`test` runs the management integration tests against isolated PostgreSQL Testcontainers, exercising migrations, persistence, HTTP endpoints, authentication, tenant rules, and lifecycle behavior. Docker/Testcontainers must be available; the tests do not use or reset the Compose database. `build` compiles the module, runs its tests/checks, and packages the Spring Boot application.

## Troubleshooting

| Symptom                                         | Checks                                                                                                                                                                                                                                                     |
| ----------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Gradle reports no matching Java toolchain       | Install/configure a Java 21 JDK and verify `java -version`; the project uses the wrapper and a Java 21 toolchain.                                                                                                                                          |
| App cannot connect to PostgreSQL                | Check that the expected PostgreSQL container is running, host port 5433 is available/mapped, `DATABASE_URL`/host/port are correct, and username/password match the initialized database. Do not recreate a persistent volume to fix a credential mismatch. |
| Compose complains about the container or volume | Inspect `nexusgate-postgres` and `nexusgate_postgres_data`; the Compose file deliberately references these existing resources. Stop and resolve ownership/configuration rather than deleting them.                                                         |
| JWT configuration prevents startup              | Set `JWT_SECRET` to at least 32 UTF-8 bytes. Check positive TTL values and nonblank issuer/audience.                                                                                                                                                       |
| Token rejected with 401                         | Check Bearer syntax, signature, expiry, issuer/audience, `userid`/`organizationid` claims, and that the persisted user and organization remain active. For Gateway verification, its secret/issuer/audience must match the issuer configuration.           |
| Authenticated request returns 403               | Confirm the persisted user has the required role (many management operations are ADMIN-only) and the organization is active.                                                                                                                               |
| Resource returns 404 unexpectedly               | Confirm the ID belongs to the caller's organization; cross-tenant resources intentionally appear not found.                                                                                                                                                |
| Flyway migration fails                          | Inspect the exact migration error and current schema/history. Do not edit an applied migration, reset the database, or delete the volume as a workaround.                                                                                                  |
| Testcontainers cannot start                     | Ensure Docker is running and accessible to the test process; tests start isolated PostgreSQL containers.                                                                                                                                                   |
| Gateway fails to parse `nexus.yml` placeholders | The Gateway resolves `${...}` placeholders from the process environment; ensure referenced values such as `JWT_SECRET`, `REDIS_HOST`, and `REDIS_PORT` are exported. The management app's `.env` import is separate.                                       |

## Implemented functionality, limitations, and future work

**Implemented in Management App:** organization registration; admin login and HS256 JWTs; database-backed user authentication and ADMIN/MEMBER management; refresh-token rotation/logout; tenant-scoped service registration, reads, configuration updates, and activation state; and API-key creation, safe metadata, service-scoped `INVOKE` permissions, expiry, activation/deactivation, and permanent revocation.

**Known limitations:** management data is not consumed by Gateway routes or filters; API keys and service permissions are not enforced on traffic; registered-service URL validation is syntax-only; usage event ingestion, retries/deduplication, metrics aggregation, daily summaries, and reports are not part of the Management App. The checked-in Gateway and Management App JWT issuer/audience defaults differ, and integration/authorization semantics have not been verified end-to-end.

**Future integration work:** agree and implement a service-configuration delivery model, Gateway API-key authentication and permission checks, consistent JWT configuration/identity semantics, and any usage/rate-limit/reporting pipeline. Update this guide after those behaviors exist and have integration tests; do not treat this planned work as current capability.
