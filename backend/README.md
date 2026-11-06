# Concert Analytics Backend

Java 17 / Spring Boot 3 API for concert search, market analytics, sentiment monitoring,
recommendations, data quality, and distributed job history. HTTP requests only read
materialized database results; Spark execution belongs to the offline job module.

## Local run

The default `local` profile uses an empty H2 file database and an in-process cache. Flyway
creates the schema at startup; development sample rows exist only under test resources.
The cache administration endpoint is disabled by default. Enable it only for local development
or behind a trusted HTTPS reverse proxy.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:ADMIN_API_ENABLED = 'true'
$env:ADMIN_USERNAME = 'admin'
$env:ADMIN_PASSWORD = '<local password>'
mvn spring-boot:run
```

The API is available at `http://localhost:8080/api/v1/health`; Actuator health is at
`http://localhost:8080/actuator/health`.

## Production configuration

Run with `SPRING_PROFILES_ACTIVE=prod` and provide:

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` for the read-only Web account
- `DB_MIGRATION_USERNAME`, `DB_MIGRATION_PASSWORD` for Flyway schema migrations
- `REDIS_HOST`, `REDIS_PORT`, and optionally `REDIS_PASSWORD`, `REDIS_DATABASE`
- `ADMIN_API_ENABLED=false` for direct HTTP deployments; only enable with HTTPS protection
- `CORS_ALLOWED_ORIGINS` as a comma-separated explicit allowlist when cross-origin access is needed

Same-origin browser access needs no CORS entry. If Redis is unavailable, cached methods continue
against MySQL and log the cache failure without failing the API response.

The distributed Compose file binds MySQL to server loopback only. For local database inspection,
open an SSH tunnel and connect the database client to the local end of the tunnel:

```powershell
ssh -N -L 13306:127.0.0.1:13306 -p <ssh-port> <user>@<server>
```

Use the read-only Web database account for inspection. Do not expose the MySQL port on `0.0.0.0`.

## Tests

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
mvn test
```
