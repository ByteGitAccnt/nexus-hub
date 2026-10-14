# NexusGate Configuration Guide

NexusGate is configured through a YAML file named `nexus.yml`. This file defines the services NexusGate routes to, the JWT verification behavior, Redis settings, and the rate-limit policy.

This guide explains what each section controls, which components are responsible for each behavior, and how Redis behaves when rate limiting is enabled or disabled.

## 1. Gateway service configuration

The `services` section defines the downstream services known to NexusGate.

```yaml
services:
  auth:
    url: http://localhost:8081
    path: /api/v1/auth/**
    publicEndpoints:
      - /login
      - /register
      - /refresh

  product:
    url: http://localhost:8083
    path: /api/v1/product/**
    management:
      endpoints:
        - health

  order:
    url: http://localhost:8082
    path: /api/v1/order/**
    management:
      endpoints:
        - metrics
```

### `url`

The destination URL of the downstream service.

```text
auth → http://localhost:8081
product → http://localhost:8083
order → http://localhost:8082
```

These values are development/debugging examples only. They are not required production ports.

### `path`

Defines the request path that NexusGate routes to the configured service.

For example:

```text
/api/v1/product/**
```

routes matching requests to the Product Service.

NexusGate acts as the single entry point while the business logic remains in the downstream service.

### `publicEndpoints`

Defines endpoints that do not require JWT authentication at the gateway.

```yaml
publicEndpoints:
  - /login
  - /register
  - /refresh
```

A public endpoint may be accessed without a token. If a client includes a Bearer token on a public endpoint, NexusGate still validates it. Invalid or malformed tokens are rejected rather than ignored.

## 2. Management endpoint configuration

```yaml
management:
  enabled: true
  basePath: /management
  targetPath: /actuator
  endpoints:
    - health
    - info
```

This section controls how NexusGate exposes downstream Spring Boot Actuator endpoints.

### `enabled`

Enables or disables management endpoint routing.

### `basePath`

The public path exposed through NexusGate.

Example:

```text
/management/product/health
```

### `targetPath`

The downstream actuator path.

Example:

```text
/actuator/health
```

NexusGate rewrites the incoming gateway request to the configured target path.

### `endpoints`

Defines the globally allowed management endpoints.

In this example:

```text
health
info
```

are globally available.

Service-level overrides can restrict access further:

```yaml
product:
  management:
    endpoints:
      - health
```

means Product exposes only `health`.

```yaml
order:
  management:
    endpoints:
      - metrics
```

means Order exposes only `metrics`.

This allows gateway management access to be controlled per service instead of exposing every actuator endpoint globally.

## 3. JWT security configuration

```yaml
security:
  enabled: true
  jwt:
    enabled: true
    algorithm: HS256
    identityClaim: userid

    verification:
      type: shared-secret
      secret: ${JWT_SECRET}

    issuer: http://localhost:8081
    audience: nexusgate
```

This section controls JWT verification performed by NexusGate.

### Global security switch

```yaml
security:
  enabled: true
```

`security.enabled` acts as the master toggle for gateway authentication. When it is `false`, the JWT gateway filter is disabled and NexusGate does not enforce token checks for protected routes. This is the top-level control for turning the security layer on or off.

### JWT verification toggle

```yaml
security:
  jwt:
    enabled: true
```

`security.jwt.enabled` is a narrower toggle for the JWT validation layer itself. It can be used to keep the configuration visible while temporarily disabling the actual verification behavior.

### Responsibility boundary

NexusGate does not generate, issue, refresh, or manage tokens.

The separate Auth Service is responsible for:

- access token generation
- refresh token validation
- token expiration
- refresh-token rotation and new token issuance

NexusGate is responsible for:

- JWT signature verification
- issuer validation
- audience validation
- extracting the configured identity claim
- establishing the authenticated principal
- propagating the verified identity to downstream services

### `algorithm`

Defines the JWT signing algorithm expected by NexusGate.

Current configuration:

```text
HS256
```

### `identityClaim`

Defines which JWT claim represents the authenticated identity.

Current configuration:

```text
userid
```

This value is used by gateway features such as the inner user-based rate limiter.

### `verification.secret`

The shared secret used to verify the JWT signature.

```yaml
secret: ${JWT_SECRET}
```

`${JWT_SECRET}` is resolved from the environment rather than being stored directly in `nexus.yml`.

### `issuer` and `audience`

NexusGate validates both values to ensure the token was issued by the expected auth system and intended for NexusGate.

## 4. Request logging configuration

```yaml
logging:
  enabled: true
  fields:
    timestamp: true
    method: true
    path: true
    service: true
    status: true
    duration: true
    requestId: true
    clientIp: true
```

This section controls the gateway's global request logging.

### `logging.enabled`

The master switch for request logging. When `false`, NexusGate skips the `LoggingGatewayFilter` altogether and performs no request instrumentation.

### `logging.fields`

Every field is independently configurable. This allows you to keep logs concise while still capturing only the information relevant to a deployment.

Available flags:

- `timestamp`
- `method`
- `path`
- `service`
- `status`
- `duration`
- `requestId`
- `clientIp`

The filter also emits and propagates the `X-Request-ID` header for request correlation across the gateway and downstream services.

## 5. Rate limiting configuration

```yaml
rateLimit:
  enabled: true
  redisFailureStrategy: fail-closed
  redisTimeoutMs: 1000

  outer:
    enabled: false
    algorithm: token-bucket
    capacity: 100
    refillRate: 100
    refillPeriodSeconds: 60

  inner:
    enabled: true
    algorithm: token-bucket
    capacity: 60
    refillRate: 60
    refillPeriodSeconds: 60
```

NexusGate provides two levels of rate limiting.

### Master `enabled` switch

```yaml
enabled: true
```

This is the master switch for the entire rate-limiting subsystem.

When:

```yaml
rateLimit:
  enabled: false
```

NexusGate does not use Redis for rate limiting.

Redis therefore becomes an optional infrastructure dependency when rate limiting is disabled.

When:

```yaml
rateLimit:
  enabled: true
```

Redis is required because the rate-limit state is stored in Redis.

## 6. Redis failure strategy

```yaml
redisFailureStrategy: fail-closed
```

This controls what happens when Redis becomes unavailable while NexusGate is running.

### `fail-closed`

```text
Redis unavailable
       ↓
Rate-limit operation cannot be completed
       ↓
503 Service Unavailable
```

The request is rejected because NexusGate cannot safely enforce the configured rate limit.

### `fail-open`

```text
Redis unavailable
       ↓
Rate-limit operation cannot be completed
       ↓
Rate limiting is temporarily bypassed
       ↓
Request continues
```

This allows downstream traffic to continue when Redis is unavailable.

This setting affects runtime Redis failures. It does not mean Redis is optional when `rateLimit.enabled=true`.

## 7. Redis command timeout

```yaml
redisTimeoutMs: 1000
```

Defines how long NexusGate waits for a Redis rate-limit operation before treating it as a failure.

Current value:

```text
1000 ms = 1 second
```

Without this timeout, a Redis connection problem could cause a request to wait significantly longer while the Redis client attempts reconnection.

The timeout allows NexusGate to quickly apply the configured `fail-open` or `fail-closed` behavior.

Lettuce may continue its background Redis reconnection process independently.

## 8. Outer rate limiter

```yaml
outer:
  enabled: true
  algorithm: token-bucket
  capacity: 100
  refillRate: 100
  refillPeriodSeconds: 60
```

The outer limiter is an IP-based rate limiter.

It protects the gateway before relying on an authenticated user identity.

The bucket key follows the structure:

```text
nexusgate:ratelimit:ip:<client-ip>
```

Example:

```text
nexusgate:ratelimit:ip:192.168.1.10
```

In the current configuration the outer limiter is enabled:

```yaml
enabled: true
```

The setting remains available so it can be disabled or tuned independently later.

## 9. Inner rate limiter

```yaml
inner:
  enabled: true
  algorithm: token-bucket
  capacity: 60
  refillRate: 60
  refillPeriodSeconds: 60
```

The inner limiter is an authenticated-user rate limiter.

It uses the authenticated identity established by the JWT gateway filter.

The bucket key follows:

```text
nexusgate:ratelimit:user:<userid>
```

This means different authenticated users receive independent rate-limit buckets.

Example:

```text
User A → nexusgate:ratelimit:user:42
User B → nexusgate:ratelimit:user:73
```

A request from User A consuming all of User A's tokens does not consume User B's tokens.

## 10. Token bucket parameters

Both outer and inner limiters use the token-bucket algorithm.

### `capacity`

The maximum number of tokens the bucket can hold.

Example:

```yaml
capacity: 6
```

A newly created bucket can therefore initially allow a burst of up to 6 requests.

### `refillRate`

Defines how many tokens are regenerated during the configured refill period.

Example:

```yaml
refillRate: 1
refillPeriodSeconds: 60
```

means:

```text
1 token every 60 seconds
```

### Lazy refill

NexusGate does not run a background job to continuously refill every bucket.

Instead, tokens are calculated when a request arrives. This keeps the implementation lightweight and avoids unnecessary background processing.

## 11. Redis configuration

```yaml
redis:
  host: ${REDIS_HOST}
  port: ${REDIS_PORT}
```

Redis connection information is supplied through environment variables.

Example:

```text
REDIS_HOST
REDIS_PORT
```

NexusGate does not hard-code environment-specific Redis connection details.

### Optional Redis behavior

Redis is required only when the rate-limiting subsystem is enabled.

Therefore:

```text
rateLimit.enabled = false
        ↓
Redis rate limiting disabled
        ↓
Redis is not initialized or required
```

Whereas:

```text
rateLimit.enabled = true
        ↓
Redis rate limiting enabled
        ↓
Redis infrastructure required
```

This makes Redis an optional dependency for users who choose not to use NexusGate's rate limiting.

## 12. Rate limit response headers

When rate limiting is active, NexusGate provides:

```text
X-RateLimit-Limit
X-RateLimit-Remaining
```

When a request is rejected because the bucket has no available token:

```text
HTTP 429 Too Many Requests
Retry-After: <seconds>
```

Example:

```text
X-RateLimit-Limit: 6
X-RateLimit-Remaining: 0
Retry-After: 54
```

## 13. Current configuration summary

The current example configuration means:

```text
JWT verification       → ENABLED
Redis infrastructure   → ENABLED
Rate limiting          → ENABLED

Outer IP limiter       → ENABLED
Inner user limiter     → ENABLED

Inner capacity         → 60 requests
Inner refill           → 60 tokens / 60 seconds

Redis failure strategy → fail-closed
Redis timeout          → 1 second
```

The configuration is intentionally flexible: individual rate-limit layers can be enabled or disabled while the master `rateLimit.enabled` switch controls whether the rate-limiting subsystem itself is active.

## 14. Monitoring configuration

```yaml
monitoring:
  enabled: true
```

- `monitoring.enabled` is the single master switch in nexus.yml. When `true` the gateway initializes metrics collection and exposes a Prometheus scrape endpoint at the default path `/management/prometheus`.
- A sample Prometheus configuration for local testing is included at `src/main/resources/prometheus.yml`.
- The gateway contains a custom metric registry and Prometheus exporter wiring in the codebase (see the Metrics package), but advanced exporter options are managed in code and are not exposed as nested YAML keys in `nexus.yml`.

## Summary

This configuration is designed to support a clean separation of responsibilities:

- Auth Service issues and manages tokens
- NexusGate verifies JWTs and enforces gateway policies
- Redis is only required when rate limiting is enabled
- the gateway supports flexible rate limiting without forcing Redis for all deployments
