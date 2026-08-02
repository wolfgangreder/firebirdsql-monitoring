package at.or.reder.firebirdsql.monitoring.statistics;

import at.or.reder.firebirdsql.monitoring.statistics.FirebirdStatisticsMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code firebird.statistics.enabled=false} disables only the
 * scheduled collection — manual gauge updates still work.
 */
@QuarkusTest
@TestProfile(FirebirdStatisticsDisabledTest.StatisticsDisabledProfile.class)
class FirebirdStatisticsDisabledTest {

    public static class StatisticsDisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("firebird.statistics.enabled", "false");
        }
    }

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
    void processStatisticsOutput_stillRegistersGaugesWhenDisabled() throws IOException {
        bean.processStatisticsOutput(SAMPLE_STATS.getBytes(StandardCharsets.UTF_8));

        Gauge gauge = registry.find("headerpage_generation").gauge();
        assertNotNull(gauge,
            "manual collection must register gauges even when scheduled collection is disabled");
        assertEquals(7.0, gauge.value(), 0.001);
    }

    @Test
    void updateDbFilePages_stillRegistersGaugeWhenDisabled() {
        bean.updateDbFilePages(8192L);

        Gauge gauge = registry.find("data_dbfile_pages").gauge();
        assertNotNull(gauge,
            "manual collection must register gauges even when scheduled collection is disabled");
        assertEquals(8192.0, gauge.value(), 0.001);
    }
}
