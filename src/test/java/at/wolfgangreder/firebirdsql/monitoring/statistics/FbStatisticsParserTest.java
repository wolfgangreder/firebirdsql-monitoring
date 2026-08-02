package at.wolfgangreder.firebirdsql.monitoring.statistics;

import at.wolfgangreder.firebirdsql.monitoring.ParsedMetric;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

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
