package at.or.reder.firebirdsql.monitoring.statistics;

import at.or.reder.firebirdsql.monitoring.statistics.FirebirdStatisticsMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

@QuarkusTest
class FirebirdStatisticsMetricsTest {

    // Minimal statistics output — does not include a "Database" header line since the
    // database name is supplied by the bean from its parsed JDBC URL, not from the text.
    private static final String SAMPLE_STATS = ""
        + "Database header page information:\n"
        + "\tFlags\t\t\t\t0\n"
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
