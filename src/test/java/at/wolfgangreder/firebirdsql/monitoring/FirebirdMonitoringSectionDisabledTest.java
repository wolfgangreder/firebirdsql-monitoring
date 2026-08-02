package at.wolfgangreder.firebirdsql.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Verifies that disabling a monitoring section registers no gauges for that
 * section while other sections stay registered.
 */
@QuarkusTest
@TestProfile(FirebirdMonitoringSectionDisabledTest.SectionsDisabledProfile.class)
class FirebirdMonitoringSectionDisabledTest {

    public static class SectionsDisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "firebird.monitoring.connections.enabled", "false",
                "firebird.monitoring.memory.enabled", "false");
        }
    }

    @Inject
    MeterRegistry registry;

    @Test
    void connectionsGaugeNotRegisteredWhenConnectionsDisabled() {
        assertNull(registry.find("firebird.connections.active").gauge(),
            "firebird.connections.active should not be registered when connections section disabled");
    }

    @Test
    void memoryGaugesNotRegisteredWhenMemoryDisabled() {
        assertNull(registry.find("firebird.memory.used_bytes").gauge(),
            "firebird.memory.used_bytes should not be registered when memory section disabled");
        assertNull(registry.find("firebird.memory.allocated_bytes").gauge(),
            "firebird.memory.allocated_bytes should not be registered when memory section disabled");
    }

    @Test
    void transactionGaugeStillRegisteredWhenOtherSectionsDisabled() {
        assertNotNull(registry.find("firebird.transactions.active").gauge(),
            "firebird.transactions.active should still be registered when other sections are disabled");
    }

    @Test
    void ioGaugesStillRegisteredWhenOtherSectionsDisabled() {
        assertNotNull(registry.find("firebird.io.page_fetches").gauge(),
            "firebird.io.page_fetches should still be registered when other sections are disabled");
        assertNotNull(registry.find("firebird.io.page_reads").gauge(),
            "firebird.io.page_reads should still be registered when other sections are disabled");
    }
}
