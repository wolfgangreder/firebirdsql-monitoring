# Firebird Statistics Metrics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port weatherlog's `DatabaseMonitoringServiceImpl` statistics-based metrics into `firebirdsql-monitoring`, adding header page, per-table, per-index, and fill distribution gauges via `FBStatisticsManager`.

**Architecture:** Three new classes — `JdbcUrlParser` (parses JDBC URL to connection info), `FbStatisticsParser` (pure function: parses `FBStatisticsManager` text output to `List<ParsedMetric>`), `FirebirdStatisticsMetrics` (`@ApplicationScoped` bean that orchestrates collection and gauge registration). Existing `FirebirdDatabaseMetrics` is not modified.

**Tech Stack:** Java 25, Quarkus, Micrometer, Jaybird (`FBStatisticsManager`), JUnit 5, `@QuarkusTest`.

---

## File Map

| Action | Path | Responsibility |
|---|---|---|
| Create | `src/main/java/at/wolfgangreder/firebirdsql/monitoring/FirebirdConnectionInfo.java` | Record: host, port, database |
| Create | `src/main/java/at/wolfgangreder/firebirdsql/monitoring/JdbcUrlParser.java` | Static URL parser |
| Create | `src/main/java/at/wolfgangreder/firebirdsql/monitoring/ParsedMetric.java` | Record: metricName, valueKey, tags, value |
| Create | `src/main/java/at/wolfgangreder/firebirdsql/monitoring/FbStatisticsParser.java` | Static statistics text parser |
| Create | `src/main/java/at/wolfgangreder/firebirdsql/monitoring/FirebirdStatisticsMetrics.java` | CDI bean: scheduler + gauge registration |
| Create | `src/test/java/at/wolfgangreder/firebirdsql/monitoring/JdbcUrlParserTest.java` | Unit tests for URL parsing |
| Create | `src/test/java/at/wolfgangreder/firebirdsql/monitoring/FbStatisticsParserTest.java` | Unit tests for statistics parsing |
| Create | `src/test/java/at/wolfgangreder/firebirdsql/monitoring/FirebirdStatisticsMetricsTest.java` | Integration tests for gauge registration |
| Modify | `src/main/resources/application.properties` | Add `firebird.statistics.interval` |

---

## Task 1: `FirebirdConnectionInfo` record and `JdbcUrlParser`

**Files:**
- Create: `src/main/java/at/wolfgangreder/firebirdsql/monitoring/FirebirdConnectionInfo.java`
- Create: `src/main/java/at/wolfgangreder/firebirdsql/monitoring/JdbcUrlParser.java`
- Create: `src/test/java/at/wolfgangreder/firebirdsql/monitoring/JdbcUrlParserTest.java`

- [ ] **Step 1.1 — Write the failing test**

```java
// src/test/java/at/wolfgangreder/firebirdsql/monitoring/JdbcUrlParserTest.java
package at.wolfgangreder.firebirdsql.monitoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdbcUrlParserTest {

    @Test
    void parse_absolutePathWithExplicitPort() {
        FirebirdConnectionInfo info =
            JdbcUrlParser.parse("jdbc:firebird://localhost:3050//var/lib/firebird/data/test.fdb");
        assertEquals("localhost", info.host());
        assertEquals(3050, info.port());
        assertEquals("/var/lib/firebird/data/test.fdb", info.database());
    }

    @Test
    void parse_relativePathWithNonStandardPort() {
        FirebirdConnectionInfo info =
            JdbcUrlParser.parse("jdbc:firebird://db.host:3051/data/test.fdb");
        assertEquals("db.host", info.host());
        assertEquals(3051, info.port());
        assertEquals("data/test.fdb", info.database());
    }

    @Test
    void parse_noPortDefaultsTo3050() {
        FirebirdConnectionInfo info =
            JdbcUrlParser.parse("jdbc:firebird://myhost//path/to/db.fdb");
        assertEquals("myhost", info.host());
        assertEquals(3050, info.port());
        assertEquals("/path/to/db.fdb", info.database());
    }

    @Test
    void parse_nullThrows() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlParser.parse(null));
    }

    @Test
    void parse_wrongSchemeThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> JdbcUrlParser.parse("jdbc:postgresql://localhost/test"));
    }

    @Test
    void parse_malformedUrlThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> JdbcUrlParser.parse("not-a-url"));
    }
}
```

- [ ] **Step 1.2 — Run test to verify it fails (compilation error expected)**

```bash
./gradlew test --tests "at.wolfgangreder.firebirdsql.monitoring.JdbcUrlParserTest"
```

Expected: BUILD FAILED — `FirebirdConnectionInfo` and `JdbcUrlParser` do not exist yet.

- [ ] **Step 1.3 — Create `FirebirdConnectionInfo` record**

```java
// src/main/java/at/wolfgangreder/firebirdsql/monitoring/FirebirdConnectionInfo.java
package at.wolfgangreder.firebirdsql.monitoring;

public record FirebirdConnectionInfo(String host, int port, String database) {}
```

- [ ] **Step 1.4 — Create `JdbcUrlParser`**

```java
// src/main/java/at/wolfgangreder/firebirdsql/monitoring/JdbcUrlParser.java
package at.wolfgangreder.firebirdsql.monitoring;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JdbcUrlParser {

    // Matches: jdbc:firebird://host[:port]/database-path
    // The database path may start with / for absolute paths (double-slash in URL = single leading slash in path).
    private static final Pattern PATTERN =
        Pattern.compile("jdbc:firebird://([^:/]+)(?::(\\d+))?/(.+)");

    private JdbcUrlParser() {}

    /**
     * Parses a Jaybird JDBC URL into its connection components.
     *
     * @param url JDBC URL of the form {@code jdbc:firebird://host[:port]/database}
     * @return parsed connection info
     * @throws IllegalArgumentException if the URL is null or does not match the expected format
     */
    public static FirebirdConnectionInfo parse(String url) {
        if (url == null) {
            throw new IllegalArgumentException("JDBC URL must not be null");
        }
        Matcher m = PATTERN.matcher(url);
        if (!m.matches()) {
            throw new IllegalArgumentException("Unrecognised Firebird JDBC URL: " + url);
        }
        String host = m.group(1);
        int port = m.group(2) != null ? Integer.parseInt(m.group(2)) : 3050;
        String database = m.group(3);
        return new FirebirdConnectionInfo(host, port, database);
    }
}
```

- [ ] **Step 1.5 — Run test to verify it passes**

```bash
./gradlew test --tests "at.wolfgangreder.firebirdsql.monitoring.JdbcUrlParserTest"
```

Expected: BUILD SUCCESSFUL, 6 tests passed.

- [ ] **Step 1.6 — Commit**

```bash
git add src/main/java/at/wolfgangreder/firebirdsql/monitoring/FirebirdConnectionInfo.java \
        src/main/java/at/wolfgangreder/firebirdsql/monitoring/JdbcUrlParser.java \
        src/test/java/at/wolfgangreder/firebirdsql/monitoring/JdbcUrlParserTest.java
git commit -m "feat: add FirebirdConnectionInfo and JdbcUrlParser"
```

---

## Task 2: `ParsedMetric` record and `FbStatisticsParser`

**Files:**
- Create: `src/main/java/at/wolfgangreder/firebirdsql/monitoring/ParsedMetric.java`
- Create: `src/main/java/at/wolfgangreder/firebirdsql/monitoring/FbStatisticsParser.java`
- Create: `src/test/java/at/wolfgangreder/firebirdsql/monitoring/FbStatisticsParserTest.java`

- [ ] **Step 2.1 — Write the failing test**

```java
// src/test/java/at/wolfgangreder/firebirdsql/monitoring/FbStatisticsParserTest.java
package at.wolfgangreder.firebirdsql.monitoring;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FbStatisticsParserTest {

    // Minimal but realistic FBStatisticsManager output.
    // Lines with real tab characters (not spaces) where the parser expects tabs.
    private static final String SAMPLE = ""
        + "Database header page information:\n"
        + "\tFlags\t\t\t0\n"
        + "\tGeneration\t\t\t42\n"
        + "\n"
        + "Analyzing database pages ...\n"
        + "WEATHER (3)\n"
        + "\tPrimary pointer page: 3, Index root page: 4\n"
        + "\tData pages: 2, data page slots: 2, average fill: 75%\n"
        + "\tFill distribution:\n"
        + "\t     0 - 19% = 0\n"
        + "\t    20 - 39% = 0\n"
        + "\t    40 - 59% = 0\n"
        + "\t    60 - 79% = 1\n"
        + "\t    80 - 99% = 1\n"
        + "\n"
        + "\t    Index WEATHER_PK (0)\n"
        + "\t\tDepth: 1, leaf buckets: 1, nodes: 5\n"
        + "\t\tAverage data length: 2.50, total dup: 0, max dup: 0\n"
        + "\t\tFill distribution:\n"
        + "\t\t     0 - 19% = 0\n"
        + "\t\t    20 - 39% = 0\n"
        + "\t\t    40 - 59% = 0\n"
        + "\t\t    60 - 79% = 2\n"
        + "\t\t    80 - 99% = 0\n"
        + "\n";

    private static final String DATABASE = "/test.fdb";

    private List<ParsedMetric> parse() throws IOException {
        return FbStatisticsParser.parse(DATABASE, SAMPLE.getBytes(StandardCharsets.UTF_8));
    }

    private Optional<ParsedMetric> find(List<ParsedMetric> metrics, String name,
                                         Map<String, String> requiredTags) {
        return metrics.stream().filter(m -> m.metricName().equals(name)
            && m.tags().entrySet().containsAll(requiredTags.entrySet())).findFirst();
    }

    @Test
    void parse_headerPageFlags() throws IOException {
        List<ParsedMetric> metrics = parse();
        Optional<ParsedMetric> m = find(metrics, "headerpage_flags",
            Map.of("dbms", "firebirdsql", "database", DATABASE));
        assertTrue(m.isPresent(), "headerpage_flags should be present");
        assertEquals(0L, m.get().value().longValue());
    }

    @Test
    void parse_headerPageGeneration() throws IOException {
        List<ParsedMetric> metrics = parse();
        Optional<ParsedMetric> m = find(metrics, "headerpage_generation",
            Map.of("dbms", "firebirdsql", "database", DATABASE));
        assertTrue(m.isPresent(), "headerpage_generation should be present");
        assertEquals(42L, m.get().value().longValue());
    }

    @Test
    void parse_tableDataPageSlots() throws IOException {
        List<ParsedMetric> metrics = parse();
        Optional<ParsedMetric> m = find(metrics, "data_data_page_slots",
            Map.of("dbms", "firebirdsql", "database", DATABASE, "object", "WEATHER"));
        assertTrue(m.isPresent(), "data_data_page_slots for WEATHER should be present");
        assertEquals(2.0, m.get().value().doubleValue(), 0.001);
    }

    @Test
    void parse_totalDataPages() throws IOException {
        List<ParsedMetric> metrics = parse();
        Optional<ParsedMetric> m = find(metrics, "data_total_data_pages",
            Map.of("dbms", "firebirdsql", "database", DATABASE));
        assertTrue(m.isPresent(), "data_total_data_pages should be present");
        assertEquals(2L, m.get().value().longValue());
    }

    @Test
    void parse_tableDistribution() throws IOException {
        List<ParsedMetric> metrics = parse();
        Optional<ParsedMetric> m = find(metrics, "data_distribution",
            Map.of("dbms", "firebirdsql", "database", DATABASE, "object", "WEATHER", "range", "79"));
        assertTrue(m.isPresent(), "data_distribution range=79 for WEATHER should be present");
        assertEquals(1.0, m.get().value().doubleValue(), 0.001);
    }

    @Test
    void parse_indexData() throws IOException {
        List<ParsedMetric> metrics = parse();
        Optional<ParsedMetric> m = find(metrics, "index_depth",
            Map.of("dbms", "firebirdsql", "database", DATABASE, "object", "WEATHER_PK"));
        assertTrue(m.isPresent(), "index_depth for WEATHER_PK should be present");
        assertEquals(1.0, m.get().value().doubleValue(), 0.001);
    }

    @Test
    void parse_indexAverageDataLength() throws IOException {
        List<ParsedMetric> metrics = parse();
        Optional<ParsedMetric> m = find(metrics, "index_average_data_length",
            Map.of("dbms", "firebirdsql", "database", DATABASE, "object", "WEATHER_PK"));
        assertTrue(m.isPresent(), "index_average_data_length for WEATHER_PK should be present");
        assertEquals(2.5, m.get().value().doubleValue(), 0.001);
    }

    @Test
    void parse_indexDistribution() throws IOException {
        List<ParsedMetric> metrics = parse();
        Optional<ParsedMetric> m = find(metrics, "index_distribution",
            Map.of("dbms", "firebirdsql", "database", DATABASE,
                   "object", "WEATHER_PK", "range", "79"));
        assertTrue(m.isPresent(), "index_distribution range=79 for WEATHER_PK should be present");
        assertEquals(2.0, m.get().value().doubleValue(), 0.001);
    }

    @Test
    void parse_emptyInput_returnsEmptyList() throws IOException {
        List<ParsedMetric> metrics = FbStatisticsParser.parse(DATABASE, new byte[0]);
        assertTrue(metrics.isEmpty());
    }

    @Test
    void parse_noAnalyzingSection_returnsHeaderOnly() throws IOException {
        String input = "Database header page information:\n\tFlags\t\t\t0\n\n";
        List<ParsedMetric> metrics = FbStatisticsParser.parse(DATABASE,
            input.getBytes(StandardCharsets.UTF_8));
        assertFalse(metrics.isEmpty());
        assertTrue(metrics.stream().noneMatch(m -> m.metricName().startsWith("data_")));
        assertTrue(metrics.stream().noneMatch(m -> m.metricName().startsWith("index_")));
        assertTrue(metrics.stream().noneMatch(m -> m.metricName().equals("data_total_data_pages")));
    }
}
```

- [ ] **Step 2.2 — Run test to verify it fails**

```bash
./gradlew test --tests "at.wolfgangreder.firebirdsql.monitoring.FbStatisticsParserTest"
```

Expected: BUILD FAILED — `ParsedMetric` and `FbStatisticsParser` do not exist.

- [ ] **Step 2.3 — Create `ParsedMetric` record**

```java
// src/main/java/at/wolfgangreder/firebirdsql/monitoring/ParsedMetric.java
package at.wolfgangreder.firebirdsql.monitoring;

import java.util.Map;

/**
 * A single metric entry parsed from FBStatisticsManager output.
 *
 * @param metricName  Micrometer gauge name (e.g. {@code "headerpage_flags"})
 * @param valueKey    Key used to look up the current value in the VALUES map inside
 *                    {@link FirebirdStatisticsMetrics}
 * @param tags        Tag key-value pairs for the gauge (e.g. dbms, database, object)
 * @param value       The parsed numeric value
 */
public record ParsedMetric(
    String metricName,
    String valueKey,
    Map<String, String> tags,
    Number value
) {}
```

- [ ] **Step 2.4 — Create `FbStatisticsParser`**

```java
// src/main/java/at/wolfgangreder/firebirdsql/monitoring/FbStatisticsParser.java
package at.wolfgangreder.firebirdsql.monitoring;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the text output of {@code FBStatisticsManager.getDatabaseStatistics()} into a list of
 * {@link ParsedMetric} entries ready for registration as Micrometer gauges.
 *
 * <p>This class is a pure function — no state, no side effects, no Micrometer dependency.
 * Metric names and tag structures mirror those produced by weatherlog's
 * {@code DatabaseMonitoringServiceImpl} for backward compatibility.
 */
public final class FbStatisticsParser {

    private static final Pattern PAT_HEADER_PAGE =
        Pattern.compile("\\A(\\t*)([^\\t]*?)(\\t*)(\\d+(\\.)?\\d*)");
    private static final Pattern PAT_DATA = Pattern.compile(
        "\\A(\\s*)(([A-Za-z ]+):\\s*)((\\d+(.?\\d+)?)%?)" +
        "(,\\s*([A-Za-z ]+):\\s*((\\d+(.?\\d+)?)%?))?" +
        "(,\\s*([A-Za-z ]+):\\s*((\\d+(.?\\d+)?)%?))?");
    private static final Pattern PAT_DISTRIBUTION =
        Pattern.compile("\\A\\s*\\d{1,2}\\s*-\\s*(\\d{1,2})%\\s*=\\s*(\\d+)");

    private FbStatisticsParser() {}

    /**
     * Parses raw {@code FBStatisticsManager} output bytes for the given database.
     *
     * @param database the database path as returned by {@code FBStatisticsManager.getDatabase()},
     *                 used as the {@code database} tag value and as a key prefix
     * @param data     raw bytes from {@code FBStatisticsManager}'s output stream
     * @return list of parsed metrics; never null, may be empty
     * @throws IOException if reading the byte array fails (in practice this should not happen)
     */
    public static List<ParsedMetric> parse(String database, byte[] data) throws IOException {
        List<ParsedMetric> result = new ArrayList<>();
        try (LineNumberReader reader = new LineNumberReader(
                new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {

            parseHeaderPage(database, reader, result);

            if (!forwardToLine(reader, "Analyzing database pages ...")) {
                return result;
            }

            long[] totalDataPages = {0};
            boolean hasMore = true;
            while (hasMore) {
                String objectName = reader.readLine();
                if (objectName == null) break;

                int indexPos = objectName.indexOf("Index ");
                if (indexPos > 0) {
                    int spacePos = objectName.indexOf(' ', indexPos + 6);
                    String indexName = spacePos > 0
                        ? objectName.substring(indexPos + 6, spacePos)
                        : objectName.substring(indexPos + 6);
                    hasMore = parseIndexData(database, indexName, reader, result);
                } else {
                    int spacePos = objectName.indexOf(' ');
                    String tableName = spacePos > 0
                        ? objectName.substring(0, spacePos)
                        : objectName;
                    hasMore = parseTableData(database, tableName, reader, result, totalDataPages);
                }
            }

            result.add(new ParsedMetric(
                "data_total_data_pages",
                database + "_data_total_data_pages",
                Map.of("dbms", "firebirdsql", "database", database),
                totalDataPages[0]
            ));
        }
        return result;
    }

    private static void parseHeaderPage(String database, LineNumberReader reader,
                                         List<ParsedMetric> result) throws IOException {
        if (!forwardToLine(reader, "Database header page information:")) return;
        String line = reader.readLine();
        while (line != null && !line.isBlank()) {
            Matcher m = PAT_HEADER_PAGE.matcher(line);
            if (m.matches()) {
                String param = toParamName(m.group(2));
                String valueStr = m.group(4);
                Number value = valueStr.indexOf('.') >= 0
                    ? Double.valueOf(valueStr) : Long.valueOf(valueStr);
                result.add(new ParsedMetric(
                    "headerpage_" + param,
                    database + "_headerpage_" + param,
                    Map.of("dbms", "firebirdsql", "database", database),
                    value
                ));
            }
            line = reader.readLine();
        }
    }

    private static boolean parseTableData(String database, String tableName,
                                           LineNumberReader reader,
                                           List<ParsedMetric> result,
                                           long[] totalDataPages) throws IOException {
        String line = reader.readLine();
        while (line != null && !line.contains("Fill distribution:")) {
            totalDataPages[0] += processDataLine(database, "data", tableName, line, result);
            line = reader.readLine();
        }
        line = reader.readLine(); // advance past "Fill distribution:" header
        while (line != null && !line.isBlank()) {
            Matcher m = PAT_DISTRIBUTION.matcher(line);
            if (m.matches()) {
                String rangeTo = m.group(1);
                double val = Double.parseDouble(m.group(2));
                result.add(new ParsedMetric(
                    "data_distribution",
                    database + "_data_" + tableName + "_distribution_" + rangeTo,
                    Map.of("dbms", "firebirdsql", "database", database,
                           "object", tableName, "range", rangeTo),
                    val
                ));
            }
            line = reader.readLine();
        }
        return line != null;
    }

    private static boolean parseIndexData(String database, String indexName,
                                           LineNumberReader reader,
                                           List<ParsedMetric> result) throws IOException {
        String line = reader.readLine();
        while (line != null && !line.contains("Fill distribution:")) {
            processDataLine(database, "index", indexName, line, result);
            line = reader.readLine();
        }
        line = reader.readLine(); // advance past "Fill distribution:" header
        while (line != null && !line.isBlank()) {
            Matcher m = PAT_DISTRIBUTION.matcher(line);
            if (m.matches()) {
                String rangeTo = m.group(1);
                double val = Double.parseDouble(m.group(2));
                result.add(new ParsedMetric(
                    "index_distribution",
                    database + "_index_" + indexName + "_distribution_" + rangeTo,
                    Map.of("dbms", "firebirdsql", "database", database,
                           "object", indexName, "range", rangeTo),
                    val
                ));
            }
            line = reader.readLine();
        }
        return line != null;
    }

    private static long processDataLine(String database, String objectGroup,
                                         String objectName, String line,
                                         List<ParsedMetric> result) {
        Matcher m = PAT_DATA.matcher(line);
        long total = 0;
        if (m.matches()) {
            total += processDataEntry(database, objectGroup, objectName,
                toParamName(m.group(3)), m.group(5), result);
            if (m.groupCount() > 10 && m.group(8) != null) {
                total += processDataEntry(database, objectGroup, objectName,
                    toParamName(m.group(8)), m.group(10), result);
            }
            if (m.groupCount() > 15 && m.group(13) != null) {
                total += processDataEntry(database, objectGroup, objectName,
                    toParamName(m.group(13)), m.group(15), result);
            }
        }
        return total;
    }

    private static long processDataEntry(String database, String objectGroup,
                                          String objectName, String param,
                                          String valueStr, List<ParsedMetric> result) {
        double val = Double.parseDouble(valueStr);
        result.add(new ParsedMetric(
            objectGroup + "_" + param,
            database + "_" + objectGroup + "_" + objectName + "_" + param,
            Map.of("dbms", "firebirdsql", "database", database, "object", objectName),
            val
        ));
        return "data_page_slots".equals(param) ? (long) val : 0L;
    }

    private static boolean forwardToLine(LineNumberReader reader, String target) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (target.equals(line)) return true;
        }
        return false;
    }

    private static String toParamName(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(Character.isAlphabetic(c) ? Character.toLowerCase(c) : '_');
        }
        return b.toString();
    }
}
```

- [ ] **Step 2.5 — Run tests to verify they pass**

```bash
./gradlew test --tests "at.wolfgangreder.firebirdsql.monitoring.FbStatisticsParserTest"
```

Expected: BUILD SUCCESSFUL, 10 tests passed.

- [ ] **Step 2.6 — Commit**

```bash
git add src/main/java/at/wolfgangreder/firebirdsql/monitoring/ParsedMetric.java \
        src/main/java/at/wolfgangreder/firebirdsql/monitoring/FbStatisticsParser.java \
        src/test/java/at/wolfgangreder/firebirdsql/monitoring/FbStatisticsParserTest.java
git commit -m "feat: add ParsedMetric and FbStatisticsParser"
```

---

## Task 3: `FirebirdStatisticsMetrics` bean

**Files:**
- Create: `src/main/java/at/wolfgangreder/firebirdsql/monitoring/FirebirdStatisticsMetrics.java`
- Create: `src/test/java/at/wolfgangreder/firebirdsql/monitoring/FirebirdStatisticsMetricsTest.java`

- [ ] **Step 3.1 — Write the failing test**

```java
// src/test/java/at/wolfgangreder/firebirdsql/monitoring/FirebirdStatisticsMetricsTest.java
package at.wolfgangreder.firebirdsql.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class FirebirdStatisticsMetricsTest {

    // Minimal statistics output — does not include a "Database" header line since the
    // database name is supplied by the bean from its parsed JDBC URL, not from the text.
    private static final String SAMPLE_STATS = ""
        + "Database header page information:\n"
        + "\tFlags\t\t\t0\n"
        + "\tGeneration\t\t\t7\n"
        + "\n"
        + "Analyzing database pages ...\n"
        + "READING (2)\n"
        + "\tPrimary pointer page: 3, Index root page: 4\n"
        + "\tData pages: 3, data page slots: 3, average fill: 50%\n"
        + "\tFill distribution:\n"
        + "\t     0 - 19% = 0\n"
        + "\t    20 - 39% = 0\n"
        + "\t    40 - 59% = 2\n"
        + "\t    60 - 79% = 1\n"
        + "\t    80 - 99% = 0\n"
        + "\n";

    @Inject
    FirebirdStatisticsMetrics bean;

    @Inject
    MeterRegistry registry;

    @Test
    void processStatisticsOutput_registersHeaderPageGauge() throws IOException {
        bean.processStatisticsOutput(SAMPLE_STATS.getBytes(StandardCharsets.UTF_8));

        Gauge gauge = registry.find("headerpage_generation").gauge();
        assertNotNull(gauge, "headerpage_generation gauge should be registered");
        assertEquals(7.0, gauge.value(), 0.001);
    }

    @Test
    void processStatisticsOutput_registersTableDataGauge() throws IOException {
        bean.processStatisticsOutput(SAMPLE_STATS.getBytes(StandardCharsets.UTF_8));

        Gauge gauge = registry.find("data_data_page_slots")
            .tag("object", "READING")
            .gauge();
        assertNotNull(gauge, "data_data_page_slots for READING should be registered");
        assertEquals(3.0, gauge.value(), 0.001);
    }

    @Test
    void processStatisticsOutput_registersTotalDataPagesGauge() throws IOException {
        bean.processStatisticsOutput(SAMPLE_STATS.getBytes(StandardCharsets.UTF_8));

        Gauge gauge = registry.find("data_total_data_pages").gauge();
        assertNotNull(gauge, "data_total_data_pages gauge should be registered");
        assertEquals(3.0, gauge.value(), 0.001);
    }

    @Test
    void processStatisticsOutput_registersDistributionGauge() throws IOException {
        bean.processStatisticsOutput(SAMPLE_STATS.getBytes(StandardCharsets.UTF_8));

        Gauge gauge = registry.find("data_distribution")
            .tag("object", "READING")
            .tag("range", "59")
            .gauge();
        assertNotNull(gauge, "data_distribution range=59 for READING should be registered");
        assertEquals(2.0, gauge.value(), 0.001);
    }

    @Test
    void updateDbFilePages_registersGaugeAndUpdatesValue() {
        bean.updateDbFilePages(8192L);

        Gauge gauge = registry.find("data_dbfile_pages").gauge();
        assertNotNull(gauge, "data_dbfile_pages gauge should be registered");
        assertEquals(8192.0, gauge.value(), 0.001);
    }

    @Test
    void updateDbFilePages_secondCallUpdatesValue() {
        bean.updateDbFilePages(100L);
        bean.updateDbFilePages(200L);

        Gauge gauge = registry.find("data_dbfile_pages").gauge();
        assertNotNull(gauge);
        assertEquals(200.0, gauge.value(), 0.001);
    }
}
```

- [ ] **Step 3.2 — Run test to verify it fails**

```bash
./gradlew test --tests "at.wolfgangreder.firebirdsql.monitoring.FirebirdStatisticsMetricsTest"
```

Expected: BUILD FAILED — `FirebirdStatisticsMetrics` does not exist.

- [ ] **Step 3.3 — Create `FirebirdStatisticsMetrics`**

```java
// src/main/java/at/wolfgangreder/firebirdsql/monitoring/FirebirdStatisticsMetrics.java
package at.wolfgangreder.firebirdsql.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.firebirdsql.gds.impl.GDSType;
import org.firebirdsql.gds.impl.wire.WireGDSFactoryPlugin;
import org.firebirdsql.management.FBStatisticsManager;
import org.firebirdsql.management.StatisticsManager;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Collects Firebird database statistics via {@code FBStatisticsManager} and exposes them as
 * Micrometer gauges.
 *
 * <p>Metric names are identical to those produced by weatherlog's
 * {@code DatabaseMonitoringServiceImpl} for backward compatibility with existing deployments.
 *
 * <p>Scheduling interval is controlled by {@code firebird.statistics.interval} (default 1h).
 * Statistics collection is expensive; do not reduce the interval significantly.
 */
@ApplicationScoped
public class FirebirdStatisticsMetrics {

    private static final Logger LOGGER =
        Logger.getLogger(FirebirdStatisticsMetrics.class.getName());

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;

    @ConfigProperty(name = "quarkus.datasource.username")
    String username;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    @Inject
    DataSource dataSource;

    @Inject
    MeterRegistry meterRegistry;

    // Package-private to allow direct inspection in tests.
    volatile FirebirdConnectionInfo connectionInfo;

    private final Map<String, Number> values = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        try {
            connectionInfo = JdbcUrlParser.parse(jdbcUrl);
        } catch (IllegalArgumentException ex) {
            LOGGER.log(Level.SEVERE,
                "Cannot parse Firebird JDBC URL — statistics collection disabled: {0}",
                ex.getMessage());
        }
    }

    /**
     * Hourly collection trigger. Fetches statistics via {@code FBStatisticsManager} and
     * updates all gauges. Errors are logged and the cycle is skipped.
     */
    @Scheduled(every = "${firebird.statistics.interval:1h}")
    void collectStatistics() {
        if (connectionInfo == null) return;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            FBStatisticsManager mgr = createStatisticsManager(connectionInfo);
            mgr.setLogger(bos);
            mgr.getDatabaseStatistics(
                StatisticsManager.DATA_TABLE_STATISTICS
                | StatisticsManager.INDEX_STATISTICS
                | StatisticsManager.RECORD_VERSION_STATISTICS);
            processStatisticsOutput(bos.toByteArray());
        } catch (IOException | SQLException ex) {
            LOGGER.log(Level.SEVERE, "Failed to collect Firebird statistics", ex);
        }
        fetchAndUpdateDbFilePages();
    }

    /**
     * Parses raw {@code FBStatisticsManager} output and registers/updates all gauges.
     * Package-private so tests can call it directly with canned data, bypassing the
     * network connection to Firebird.
     */
    void processStatisticsOutput(byte[] data) throws IOException {
        if (connectionInfo == null) return;
        List<ParsedMetric> metrics = FbStatisticsParser.parse(connectionInfo.database(), data);
        for (ParsedMetric m : metrics) {
            values.put(m.valueKey(), m.value());
            registerGauge(m);
        }
    }

    /**
     * Updates the {@code data_dbfile_pages} gauge with the given page count.
     * Package-private so tests can call it directly without a DataSource connection.
     */
    void updateDbFilePages(long pages) {
        if (connectionInfo == null) return;
        String key = connectionInfo.database() + "_data_dbfile_pages";
        String db  = connectionInfo.database();
        values.put(key, pages);
        Gauge.builder("data_dbfile_pages",
                () -> {
                    Number n = values.get(key);
                    return n != null ? n.doubleValue() : Double.NaN;
                })
            .tag("dbms", "firebirdsql")
            .tag("database", db)
            .register(meterRegistry);
    }

    private void registerGauge(ParsedMetric metric) {
        String key = metric.valueKey();
        Gauge.Builder<?> builder = Gauge.builder(metric.metricName(),
            () -> {
                Number n = values.get(key);
                return n != null ? n.doubleValue() : Double.NaN;
            });
        metric.tags().forEach((k, v) -> builder.tag(k, v));
        builder.register(meterRegistry);
    }

    private void fetchAndUpdateDbFilePages() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MON$PAGES FROM MON$DATABASE")) {
            if (rs.next()) {
                updateDbFilePages(rs.getLong(1));
            }
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE,
                "Failed to collect data_dbfile_pages from MON$DATABASE", ex);
        }
    }

    private FBStatisticsManager createStatisticsManager(FirebirdConnectionInfo info) {
        FBStatisticsManager mgr = new FBStatisticsManager(
            GDSType.getType(WireGDSFactoryPlugin.PURE_JAVA_TYPE_NAME));
        mgr.setCharSet("UTF-8");
        mgr.setUser(username);
        mgr.setPassword(password);
        mgr.setServerName(info.host());
        mgr.setPortNumber(info.port());
        mgr.setDatabase(info.database());
        return mgr;
    }
}
```

- [ ] **Step 3.4 — Run tests to verify they pass**

```bash
./gradlew test --tests "at.wolfgangreder.firebirdsql.monitoring.FirebirdStatisticsMetricsTest"
```

Expected: BUILD SUCCESSFUL, 6 tests passed.

- [ ] **Step 3.5 — Run all tests to confirm no regressions**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL, all existing tests still pass.

- [ ] **Step 3.6 — Commit**

```bash
git add src/main/java/at/wolfgangreder/firebirdsql/monitoring/FirebirdStatisticsMetrics.java \
        src/test/java/at/wolfgangreder/firebirdsql/monitoring/FirebirdStatisticsMetricsTest.java
git commit -m "feat: add FirebirdStatisticsMetrics bean with FBStatisticsManager collection"
```

---

## Task 4: Configuration and final verification

**Files:**
- Modify: `src/main/resources/application.properties`

- [ ] **Step 4.1 — Add `firebird.statistics.interval` property**

Add the following block to `src/main/resources/application.properties` after the existing `firebird.monitoring.interval` section:

```properties
# =============================================================================
# Firebird statistics collection interval (FBStatisticsManager — expensive)
# Reads full table/index statistics; keep at 1h or longer.
# Can be overridden via environment variable FIREBIRD_STATISTICS_INTERVAL
# =============================================================================
firebird.statistics.interval=${FIREBIRD_STATISTICS_INTERVAL:1h}
```

- [ ] **Step 4.2 — Build and run all tests**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4.3 — Verify new gauges appear in metrics output**

Build the application and confirm the Quarkus app starts cleanly (no `@PostConstruct` errors, correct config binding):

```bash
./gradlew build -x test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL with no errors.

- [ ] **Step 4.4 — Final commit**

```bash
git add src/main/resources/application.properties
git commit -m "feat: add firebird.statistics.interval config property"
```

---

## Self-Review Notes

- **Spec coverage:** All 7 missing metric types are covered: `headerpage_*` (Task 2/3), `data_*` per-table (Task 2/3), `index_*` per-index (Task 2/3), `data_total_data_pages` (Task 2/3), `data_dbfile_pages` (Task 3), `data_distribution` (Task 2/3), `index_distribution` (Task 2/3).
- **No placeholders:** All code is complete. All test assertions are concrete.
- **Type consistency:** `FirebirdConnectionInfo` record used in `JdbcUrlParser` (Task 1) and `FirebirdStatisticsMetrics` (Task 3). `ParsedMetric` record defined in Task 2 and consumed in Task 3. Method names `processStatisticsOutput` and `updateDbFilePages` are consistent across bean implementation and test calls.
- **Backward compatibility:** Metric names and tag structures match weatherlog exactly — no prefix added.
