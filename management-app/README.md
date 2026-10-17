# Nexus management application

The management app is a separate servlet-based Spring Boot application on port `8090`. It is not registered as a gateway route. It uses PostgreSQL and applies versioned schema changes with Flyway; Hibernate is configured to validate the schema rather than modify it.

## Local PostgreSQL configuration

Keep local database settings in the repository-root `.env`; do not create another `.env` under this module. The root `.env` is ignored by Git. Copy `.env.example` to `.env` and set a local database password there. Docker Compose reads the root `.env` for PostgreSQL initialization; its PostgreSQL-only service uses the existing `nexusgate_postgres_data` named volume and publishes container port `5432` on `127.0.0.1:5433`.

Spring Boot does not load `.env` automatically. The management application explicitly imports `../.env` as a properties file, relative to its Gradle project working directory, when launched with `.\gradlew.bat :management-app:bootRun` from the repository root. The JDBC defaults point to `jdbc:postgresql://localhost:5433/nexusgate` with user `nexusgate_app`; `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` can override those defaults, while `POSTGRES_DB`, `POSTGRES_USER`, and `POSTGRES_PASSWORD` supply the default database credentials.

The Gradle `bootRun` task sets the management JVM timezone to UTC. This avoids a PostgreSQL connection failure when the host Java timezone identifier is unsupported by the PostgreSQL container. For a packaged deployment in the same environment, start the JVM with `-Duser.timezone=UTC`.

Before starting Compose, inspect the existing `nexusgate-postgres` container. The Compose service deliberately references the existing named volume and container name; do not remove the container or volume to resolve a Compose conflict. If Compose cannot safely reuse the existing container, keep using that running container and stop rather than create a second database. Starting the management app runs Flyway against the configured database and may apply the existing versioned migration. No Redis, Prometheus, or gateway service is defined here.

## API-key management

API-key management is available only to active organization administrators through `management-app`. New API keys are returned as raw `ng_live_` secrets once at creation; the database stores only a SHA-256 hash and a non-authenticating lookup prefix. List, detail, status, and revocation responses contain metadata and service permissions but never a secret or hash.

Each new key requires at least one service permission. The supported permission is `INVOKE`; it grants invocation eligibility for an active registered service with `auth_mode = API_KEY` in the same organization as the key. The database enforces organization consistency with composite foreign keys. Key lifecycle status is `ACTIVE`, `INACTIVE`, or permanently `REVOKED`; expiry is an independent timestamp. Expired keys cannot be reactivated.

Migration V4 preserves the former free-form `service_slug` permission table as `api_key_service_permissions_legacy`. Those old strings have no safe automatic mapping because registered services use UUID IDs and no slug exists. Legacy rows are retained but are not interpreted as UUID-based permissions; audit and explicit mapping are required before any future Gateway authorization can rely on them. This management implementation does not authenticate API keys, configure Gateway routes, make outbound requests, rate-limit traffic, or record usage.
