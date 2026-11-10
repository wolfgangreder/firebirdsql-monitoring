package at.or.reder.firebirdsql.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Tracks whether the Firebird database is reachable.
 *
 * <p>A background job runs a trivial query every {@code firebird.monitoring.interval} and
 * records the outcome. The result is exposed as the {@code firebird_up} gauge (1 = up,
 * 0 = down) and {@code firebird_last_success_timestamp_seconds}. Reading the gauges or
 * {@link #isUp()} never touches the database, so scrapes and health probes cannot block
 * on a stale connection.
 *
 * <p>If no result has been recorded for {@value #STALE_FACTOR} intervals (e.g. the check
 * itself is stuck) the database is reported as down.
 */
@ApplicationScoped
public class FirebirdConnectivityMonitor {

    static final int STALE_FACTOR = 3;
    private static final String PROBE_SQL = "SELECT 1 FROM RDB$DATABASE";
    private static final int PROBE_QUERY_TIMEOUT_SECONDS = 5;

    private static final Logger LOGGER = Logger.getLogger(FirebirdConnectivityMonitor.class.getName());

    @Inject
    DataSource dataSource;

    @Inject
    MeterRegistry registry;

    @ConfigProperty(name = "firebird.monitoring.interval", defaultValue = "30s")
    Duration interval;

    /** Time source in epoch millis; replaceable in tests. */
    LongSupplier clock = System::currentTimeMillis;

    private volatile boolean lastAttemptSucceeded;
    private volatile long lastAttemptMillis;
    private volatile long lastSuccessMillis;
    private volatile String lastError;

    void onStart(@Observes StartupEvent ev) {
        registerMeters();
    }

    void registerMeters() {
        Gauge.builder("firebird.up", this, m -> m.isUp() ? 1.0 : 0.0)
                .description("1 if the last Firebird connectivity check succeeded recently, otherwise 0")
                .register(registry);
        Gauge.builder("firebird.last_success_timestamp", this, m -> m.lastSuccessMillis / 1000.0)
                .description("Unix time of the last successful Firebird connectivity check (0 = never)")
                .baseUnit("seconds")
                .register(registry);
    }

    @Scheduled(every = "${firebird.monitoring.interval:30s}",
            identity = "firebird-connectivity",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void check() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(PROBE_QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = stmt.executeQuery(PROBE_SQL)) {
                if (rs.next()) {
                    recordSuccess();
                } else {
                    recordFailure(new IllegalStateException("connectivity query returned no row"));
                }
            }
        } catch (Exception e) {
            recordFailure(e);
        }
    }

    void recordSuccess() {
        long now = clock.getAsLong();
        lastSuccessMillis = now;
        lastError = null;
        lastAttemptMillis = now;
        lastAttemptSucceeded = true;
    }

    void recordFailure(Throwable cause) {
        boolean wasUp = lastAttemptSucceeded;
        lastError = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
        lastAttemptMillis = clock.getAsLong();
        lastAttemptSucceeded = false;
        if (wasUp) {
            LOGGER.log(Level.WARNING, "Firebird database became unreachable: {0}", lastError);
        } else {
            LOGGER.log(Level.FINE, "Firebird database still unreachable: {0}", lastError);
        }
    }

    /** {@code true} if the last check succeeded and is not older than {@value #STALE_FACTOR} intervals. */
    public boolean isUp() {
        if (!lastAttemptSucceeded) {
            return false;
        }
        long age = clock.getAsLong() - lastAttemptMillis;
        return age <= interval.toMillis() * STALE_FACTOR;
    }

    /** Message of the last failed check, empty if the last check succeeded. */
    public Optional<String> lastError() {
        return Optional.ofNullable(lastError);
    }
}
