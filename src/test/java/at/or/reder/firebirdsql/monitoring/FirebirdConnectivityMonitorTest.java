package at.or.reder.firebirdsql.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that database reachability is tracked in a background check and exposed as
 * {@code firebird_up} / {@code firebird_last_success_timestamp_seconds} gauges, without
 * any DB access when the gauges are read.
 */
class FirebirdConnectivityMonitorTest {

    private static final long NOW = 1_800_000_000_000L;

    private final AtomicLong clock = new AtomicLong(NOW);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private DataSource dataSource;
    private FirebirdConnectivityMonitor monitor;

    @BeforeEach
    void setUp() {
        dataSource = mock(DataSource.class);
        monitor = new FirebirdConnectivityMonitor();
        monitor.dataSource = dataSource;
        monitor.registry = registry;
        monitor.interval = Duration.ofSeconds(30);
        monitor.clock = clock::get;
        monitor.registerMeters();
    }

    private double up() {
        Gauge g = registry.find("firebird.up").gauge();
        assertNotNull(g, "firebird.up gauge must be registered");
        return g.value();
    }

    private double lastSuccessSeconds() {
        Gauge g = registry.find("firebird.last_success_timestamp").gauge();
        assertNotNull(g, "firebird.last_success_timestamp gauge must be registered");
        return g.value();
    }

    @Test
    void reportsDownBeforeFirstCheck() {
        assertEquals(0.0, up());
        assertFalse(monitor.isUp());
    }

    @Test
    void reportsUpAfterSuccessfulCheck() throws Exception {
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery("SELECT 1 FROM RDB$DATABASE")).thenReturn(rs);
        when(rs.next()).thenReturn(true);

        monitor.check();

        assertEquals(1.0, up());
        assertEquals(NOW / 1000.0, lastSuccessSeconds(), 0.001);
        assertTrue(monitor.lastError().isEmpty());
    }

    @Test
    void reportsDownWhenConnectionFails() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        monitor.check();

        assertEquals(0.0, up());
        assertEquals("connection refused", monitor.lastError().orElseThrow());
    }

    @Test
    void keepsLastSuccessTimestampAfterFailure() {
        monitor.recordSuccess();
        clock.addAndGet(10_000);
        monitor.recordFailure(new SQLException("boom"));

        assertEquals(0.0, up());
        assertEquals(NOW / 1000.0, lastSuccessSeconds(), 0.001);
    }

    @Test
    void recoversAfterFailure() {
        monitor.recordFailure(new SQLException("boom"));
        monitor.recordSuccess();

        assertEquals(1.0, up());
        assertTrue(monitor.lastError().isEmpty());
    }

    @Test
    void reportsDownWhenLastCheckIsStale() {
        monitor.recordSuccess();
        // check hangs / scheduler stalled: no new result for more than 3 intervals
        clock.addAndGet(Duration.ofSeconds(91).toMillis());

        assertEquals(0.0, up());
    }

    @Test
    void reportsUpWithinStalenessWindow() {
        monitor.recordSuccess();
        clock.addAndGet(Duration.ofSeconds(89).toMillis());

        assertEquals(1.0, up());
    }

    @Test
    void checkNeverThrows() throws Exception {
        when(dataSource.getConnection()).thenThrow(new IllegalStateException("pool closed"));

        assertDoesNotThrow(monitor::check);
        assertEquals(0.0, up());
    }
}
