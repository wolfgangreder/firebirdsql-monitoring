# Firebird SQL Prometheus Exporter

> Prometheus / Micrometer metrics exporter for [Firebird SQL](https://firebirdsql.org/) — Kubernetes-ready, built with [Quarkus](https://quarkus.io/).

[![Docker Hub](https://img.shields.io/docker/v/wolfgangreder/at.or.reder.firebird-monitoring?label=Docker%20Hub)](https://hub.docker.com/r/wolfgangreder/at.or.reder.firebird-monitoring)
[![License](https://img.shields.io/github/license/wolfgangreder/firebirdsql-monitoring)](LICENSE)

Scrapes Firebird's `MON$` monitoring tables and exposes the data as Prometheus gauges on `/q/metrics`. Supports Firebird 3.x / 4.x / 5.x.

## Quick start

```bash
docker run -p 8080:8080 \
  -e FIREBIRD_JDBC_URL="jdbc:firebird://<host>:<port>//<db-path>" \
  -e FIREBIRD_USER=sysdba \
  -e FIREBIRD_PASSWORD=<password> \
  wolfgangreder/at.or.reder.firebird-monitoring:latest
```

Metrics: `http://localhost:8080/q/metrics`

## Exposed metrics

| Metric | Description |
|---|---|
| `firebird_connections_active` | Active connections |
| `firebird_transactions_active` | Active transactions |
| `firebird_statements_active` | Active statements |
| `firebird_io_page_fetches_pages` | Page fetches |
| `firebird_io_page_reads_pages` | Page reads from disk |
| `firebird_io_page_writes_pages` | Page writes to disk |
| `firebird_io_page_marks_pages` | Dirty pages |
| `firebird_memory_used_bytes` | Memory in use |
| `firebird_memory_allocated_bytes` | Memory allocated |
| `firebird_up` | `1` if the database was reachable at the last check, `0` if not (alert on this) |
| `firebird_last_success_timestamp_seconds` | Unix time of the last successful connectivity check |

Statistics metrics (`headerpage_*`, `data_*`, `index_*`) collected separately on a slower interval.

## Configuration

| Environment variable | Default | Description |
|---|---|---|
| `FIREBIRD_JDBC_URL` | — | Jaybird JDBC URL |
| `FIREBIRD_USER` | `sysdba` | Database user |
| `FIREBIRD_PASSWORD` | — | Database password |
| `FIREBIRD_MONITORING_INTERVAL` | `30s` | MON$ scrape interval |
| `FIREBIRD_MONITORING_CONNECTIONS_ENABLED` | `true` | Collect connections |
| `FIREBIRD_MONITORING_TRANSACTIONS_ENABLED` | `true` | Collect transactions |
| `FIREBIRD_MONITORING_STATEMENTS_ENABLED` | `true` | Collect statements |
| `FIREBIRD_MONITORING_IO_ENABLED` | `true` | Collect I/O stats |
| `FIREBIRD_MONITORING_MEMORY_ENABLED` | `true` | Collect memory stats |
| `FIREBIRD_STATISTICS_ENABLED` | `true` | Collect DB statistics |
| `FIREBIRD_STATISTICS_INTERVAL` | `1h` | Statistics scrape interval |
| `FIREBIRD_SO_TIMEOUT_MS` | `10000` | JDBC socket read timeout (ms) |
| `FIREBIRD_CONNECT_TIMEOUT_S` | `5` | JDBC connect timeout (s) |
| `FIREBIRD_STATISTICS_SO_TIMEOUT` | `10m` | Statistics service socket timeout |
| `FIREBIRD_STATISTICS_CONNECT_TIMEOUT` | `5s` | Statistics service connect timeout |
| `FIREBIRD_WORKER_THREADS` | `20` | Max blocking worker threads |

## REST API

| Path | Description |
|---|---|
| `GET /q/metrics` | Prometheus metrics |
| `GET /q/health/live` | Liveness probe (never touches the database) |
| `GET /q/health/ready` | Readiness probe (independent of the database; DB state is shown as informational data and in `firebird_up`) |
| `GET /q/health/started` | Startup probe |
| `GET /monitoring/database` | DB info (JSON) |
| `GET /monitoring/connections` | Active connections (JSON) |
| `GET /monitoring/transactions` | Active transactions (JSON) |

## Kubernetes / Helm

See [`kubernetes/README.md`](kubernetes/README.md) — includes plain kubectl manifests, Helm chart, and template config files.

## Build from source

**Prerequisites:** JDK 25+, Gradle wrapper included.

```bash
./gradlew build

# dev mode (live reload)
./gradlew quarkusDev \
  -Dquarkus.datasource.jdbc.url="jdbc:firebird://<host>:<port>//<db-path>" \
  -Dquarkus.datasource.username=sysdba

# build container image
docker build -f src/main/docker/Dockerfile.jvm \
  -t wolfgangreder/at.or.reder.firebird-monitoring:latest .
```

## License

[LICENSE](LICENSE)
