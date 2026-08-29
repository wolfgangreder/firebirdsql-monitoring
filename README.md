# firebirdsql-monitoring

A Kubernetes/container-friendly Quarkus application that monitors a [Firebird SQL](https://firebirdsql.org/) database and exposes metrics via [Micrometer](https://micrometer.io/) / Prometheus.

## Features

- **Firebird SQL monitoring** – queries `MON$ATTACHMENTS`, `MON$TRANSACTIONS`, `MON$STATEMENTS`, `MON$IO_STATS`, and `MON$MEMORY_USAGE` to expose live database health data.
- **Micrometer / Prometheus metrics** – all collected statistics are exposed as gauges on the `/q/metrics` endpoint.
- **SmallRye Health probes** – dedicated liveness (`/q/health/live`) and readiness (`/q/health/ready`) endpoints for Kubernetes.
- **Kubernetes manifests** – generated automatically by the `quarkus-kubernetes` extension during `./gradlew build`.
- **Container-ready** – JVM-mode `Dockerfile.jvm` included; build output targets a minimal OCI image.
- **Gradle Groovy** build system.

## Prerequisites

| Tool | Version |
|------|---------|
| JDK  | 17+     |
| Gradle | 9.x (wrapper included) |
| Firebird SQL | 3.x / 4.x / 5.x |

## Quick start

```bash
# Clone and build
git clone https://github.com/wolfgangreder/firebirdsql-monitoring.git
cd firebirdsql-monitoring
./gradlew build

# Run in dev mode (live reload)
./gradlew quarkusDev \
  -Dquarkus.datasource.jdbc.url="jdbc:firebird://<replace-with-host>:<replace-with-port>//<replace-with-db-path>" \
  -Dquarkus.datasource.username=sysdba
```

## Configuration

All settings can be overridden through environment variables:

| Environment variable | Default | Description |
|---|---|---|
| `FIREBIRD_JDBC_URL` | `jdbc:firebird://<host>:<port>//<db-path>` | Jaybird JDBC URL |
| `FIREBIRD_USER` | `sysdba` | Database user |
| `FIREBIRD_PASSWORD` | `masterkey` | Database credential |
| `FIREBIRD_MONITORING_INTERVAL` | `30s` | MON$ gauge collection interval (ISO-8601 or `Ns`) |
| `FIREBIRD_MONITORING_CONNECTIONS_ENABLED` | `true` | Enable/disable scheduled connections gauge collection |
| `FIREBIRD_MONITORING_TRANSACTIONS_ENABLED` | `true` | Enable/disable scheduled transactions gauge collection |
| `FIREBIRD_MONITORING_STATEMENTS_ENABLED` | `true` | Enable/disable scheduled statements gauge collection |
| `FIREBIRD_MONITORING_IO_ENABLED` | `true` | Enable/disable scheduled IO gauge collection |
| `FIREBIRD_MONITORING_MEMORY_ENABLED` | `true` | Enable/disable scheduled memory gauge collection |
| `FIREBIRD_STATISTICS_INTERVAL` | `1h` | Statistics collection interval — keep at 1h or longer |
| `FIREBIRD_STATISTICS_ENABLED` | `true` | Enable/disable scheduled statistics collection (manual `/statistics` always available) |

## REST API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/monitoring/database` | General database info from `MON$DATABASE` |
| GET | `/monitoring/connections` | Active connections from `MON$ATTACHMENTS` |
| GET | `/monitoring/transactions` | Active transactions from `MON$TRANSACTIONS` |
| GET | `/q/metrics` | Prometheus-formatted metrics |
| GET | `/q/health/live` | Kubernetes liveness probe |
| GET | `/q/health/ready` | Kubernetes readiness probe |

## Exposed Metrics

| Metric name | Description |
|---|---|
| `firebird_connections_active` | Active connections (excl. monitoring connection) |
| `firebird_transactions_active` | Active transactions |
| `firebird_statements_active` | Active statements in other connections |
| `firebird_io_page_fetches_pages` | Page fetches (database level) |
| `firebird_io_page_reads_pages` | Page reads from disk (database level) |
| `firebird_io_page_writes_pages` | Page writes to disk (database level) |
| `firebird_io_page_marks_pages` | Dirty pages (database level) |
| `firebird_memory_used_bytes` | Memory in use by the engine |
| `firebird_memory_allocated_bytes` | Memory allocated by the engine |

## Container build

```bash
# Build application
./gradlew build

# Build Docker image
docker build -f src/main/docker/Dockerfile.jvm \
  -t wolfgangreder/at.or.reder.firebird-monitoring:latest .

# Run container
docker run -p 8080:8080 \
  -e FIREBIRD_JDBC_URL="jdbc:firebird://<replace-with-host>:<replace-with-port>//<replace-with-db-path>" \
  -e FIREBIRD_USER=sysdba \
  -e FIREBIRD_PASSWORD=secret \
  wolfgangreder/at.or.reder.firebird-monitoring:latest
```

## Kubernetes / Helm

See [`kubernetes/README.md`](kubernetes/README.md) for plain kubectl and Helm deployment instructions, template files, and configuration reference.

## License

See [LICENSE](LICENSE).
