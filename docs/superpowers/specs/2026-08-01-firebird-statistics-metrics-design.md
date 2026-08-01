# Design: Firebird Statistics Metrics

**Date:** 2026-08-01  
**Project:** firebirdsql-monitoring  
**Source:** ported from weatherlog `DatabaseMonitoringServiceImpl`

## Problem

`firebirdsql-monitoring` only collects MON$-table metrics (connections, transactions, statements, I/O, memory). It is missing the statistics-based metrics that exist in the `weatherlog` project's `DatabaseMonitoringServiceImpl`:

- `headerpage_<param>` — database header page fields
- `data_<param>` per table (with `object=<table>` tag)
- `index_<param>` per index (with `object=<index>` tag)
- `data_total_data_pages` — sum of data page slots across all tables
- `data_dbfile_pages` — `mon$pages` from `mon$database`
- `data_distribution` / `index_distribution` — fill distribution buckets per object

## Goals

- Full metric parity with weatherlog for existing deployments (names/tags unchanged).
- Reuse existing JDBC URL and credentials config — no duplicate properties.
- Testable without a real Firebird instance (pure parsing logic separated out).

## Non-goals

- Replacing or modifying `FirebirdDatabaseMetrics` (MON$ metrics stay as-is).
- Changing existing metric names or tags.

## Architecture

Three new classes in `at.wolfgangreder.firebirdsql.monitoring`:

### `FirebirdConnectionInfo` (record)
```
record FirebirdConnectionInfo(String host, int port, String database)
```
Plain data carrier.

### `JdbcUrlParser` (static utility)
Parses `jdbc:firebird://host:port/database` (both absolute and relative paths) into `FirebirdConnectionInfo`.  
Throws `IllegalArgumentException` on unrecognised format.

Regex: `jdbc:firebird://([^:/]+):(\d+)/(.*)`

### `FbStatisticsParser` (static utility)
Parses the raw text output of `FBStatisticsManager.getDatabaseStatistics()` into a `Map<String, Number>`.

Key format matches weatherlog exactly:
- Header page: `<param>` (e.g., `flags`, `checksum`, `generation`)
- Per-table data: `<database>data<tableName><param>` (e.g., `data_page_slots`, `data_pages`)
- Per-index: `<database>index<indexName><param>`
- Data distribution: `<database>data<tableName>distribution<rangeTo>`
- Index distribution: `<database>index<indexName>distribution<rangeTo>`
- Total data pages: `<database>_data_total_data_pages`
- DB file pages: `<database>_data_dbfile_pages`

No Micrometer dependency. Pure function — takes bytes, returns map.

Regex patterns (from weatherlog):
```java
PAT_HEADER_PAGE = Pattern.compile("\\A(\\t*)([^\\t]*?)(\\t*)(\\d+(\\.)?\\d*)");
PAT_DATA = Pattern.compile(
    "\\A(\\s*)(([A-Za-z ]+):\\s*)((\\d+(.?\\d+)?)%?)" +
    "(,\\s*([A-Za-z ]+):\\s*((\\d+(.?\\d+)?)%?))?" +
    "(,\\s*([A-Za-z ]+):\\s*((\\d+(.?\\d+)?)%?))?");
PAT_DISTRIBUTION = Pattern.compile("\\A\\s*\\d{1,2}\\s*-\\s*(\\d{1,2})%\\s*=\\s*(\\d+)");
```

### `FirebirdStatisticsMetrics` (`@ApplicationScoped`)

Injected config:
- `quarkus.datasource.jdbc.url` → parsed via `JdbcUrlParser` at startup
- `quarkus.datasource.username`
- `quarkus.datasource.password`
- `firebird.statistics.interval` (default `1h`, env `FIREBIRD_STATISTICS_INTERVAL`)

Injected beans: `DataSource`, `MeterRegistry`.

Owns a `ConcurrentHashMap<String, Number> VALUES` instance field (not static — unlike weatherlog — to support multiple instances in tests).

**Gauge registration:** `Gauge.builder(name, () -> VALUES.get(key)).tag(...).register(meterRegistry)` — Micrometer deduplicates by meter ID, so calling `register()` on repeated collections is safe.

**Schedule:** `@Scheduled(every = "${firebird.statistics.interval:1h}")`

**Collection flow:**
1. Build `FBStatisticsManager` from `FirebirdConnectionInfo` + user/pass.
2. Call `getDatabaseStatistics(DATA_TABLE_STATISTICS | INDEX_STATISTICS | RECORD_VERSION_STATISTICS)` → `byte[]`.
3. `FbStatisticsParser.parse(database, bytes)` → `Map<String, Number>`.
4. For each entry: put into VALUES, call `createGauge(...)`.
5. `DataSource.getConnection()` → `SELECT mon$pages FROM mon$database` → update `data_dbfile_pages`.

## Metric Names & Tags (backward-compatible)

All metrics carry `dbms=firebirdsql` and `database=<db>` tags.

| Metric | Extra tags | Source |
|---|---|---|
| `headerpage_<param>` | — | DB header page |
| `data_<param>` | `object=<table>` | Per-table statistics |
| `index_<param>` | `object=<index>` | Per-index statistics |
| `data_total_data_pages` | — | Sum of `data_page_slots` |
| `data_dbfile_pages` | — | `mon$pages` |
| `data_distribution` | `object=<table>`, `range=<x>` | Table fill distribution |
| `index_distribution` | `object=<index>`, `range=<x>` | Index fill distribution |

## Configuration

Add to `application.properties`:
```properties
# Statistics collection interval (FBStatisticsManager, expensive — default hourly)
firebird.statistics.interval=${FIREBIRD_STATISTICS_INTERVAL:1h}
```

No new Firebird connection properties needed — JDBC URL and credentials are reused.

## Error Handling

| Scenario | Behaviour |
|---|---|
| Unparseable JDBC URL | Log SEVERE at startup; `FirebirdStatisticsMetrics` skips all collection |
| `SQLException` / `IOException` during collection | Log SEVERE; skip cycle, retry next interval |
| Unparseable statistics line | Skip line, continue parsing (guarded by `matcher.matches()`) |
| `mon$pages` query failure | Log SEVERE; `data_dbfile_pages` retains last value |

## Testing

### `JdbcUrlParserTest`
Unit tests (no external deps):
- Absolute path URL: `jdbc:firebird://db.host:3050//var/lib/firebird/data/test.fdb`
- Relative path URL: `jdbc:firebird://localhost:3051/data/test.fdb`
- Non-standard port
- Missing port → `IllegalArgumentException`
- Non-firebird scheme → `IllegalArgumentException`
- Null input → `IllegalArgumentException`

### `FbStatisticsParserTest`
Unit tests with hardcoded sample output strings:
- Header page section parses correctly
- Per-table entry yields correct key/value
- Per-index entry yields correct key/value
- Distribution buckets parse correctly
- Total data pages accumulates across tables
- Blank/garbage lines are skipped without exception

### `FirebirdStatisticsMetricsTest`
Quarkus test (scheduler disabled via existing test profile):
- Inject `FirebirdStatisticsMetrics` with `SimpleMeterRegistry` and mock `DataSource`
- Call `collectStatistics()` with canned byte[] input
- Assert gauges registered with correct names, tags, and values
- Assert `data_dbfile_pages` gauge updated from mock DataSource result

## Files to Create / Modify

**New files:**
- `src/main/java/.../FirebirdConnectionInfo.java` (record)
- `src/main/java/.../JdbcUrlParser.java`
- `src/main/java/.../FbStatisticsParser.java`
- `src/main/java/.../FirebirdStatisticsMetrics.java`
- `src/test/java/.../JdbcUrlParserTest.java`
- `src/test/java/.../FbStatisticsParserTest.java`
- `src/test/java/.../FirebirdStatisticsMetricsTest.java`

**Modified files:**
- `src/main/resources/application.properties` — add `firebird.statistics.interval` property
