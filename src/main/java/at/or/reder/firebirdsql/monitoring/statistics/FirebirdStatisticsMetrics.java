package at.or.reder.firebirdsql.monitoring.statistics;

import at.or.reder.firebirdsql.monitoring.ParsedMetric;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Collects Firebird database statistics via {@code FBStatisticsManager} and exposes them as
 * Micrometer gauges.
 *
 * <p>Metric names are identical to those produced by weatherlog's
 * {@code DatabaseMonitoringServiceImpl} for backward compatibility with existing deployments.
 *
 * <p>Scheduling interval is controlled by {@code firebird.statistics.interval} (default 1h).
 * Statistics collection is expensive; do not reduce the interval significantly.
 * Set {@code firebird.statistics.enabled=false} to disable scheduled collection only;
 * manual collection via the update methods remains possible.
 */
@ApplicationScoped
public class FirebirdStatisticsMetrics {

    private static final Logger LOGGER =
        Logger.getLogger(FirebirdStatisticsMetrics.class.getName());

    @Inject
    FirebirdStatisticsFetcher fetcher;

    @Inject
    DataSource dataSource;

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "firebird.statistics.enabled", defaultValue = "true")
    boolean statisticsEnabled;

    private final Map<String, Number> values = new ConcurrentHashMap<>();
    /** Keys of gauges already registered; prevents duplicate Micrometer registrations. */
    private final Set<String> registeredKeys = ConcurrentHashMap.newKeySet();

    /**
     * Hourly collection trigger. Fetches statistics via {@code FBStatisticsManager} and
     * updates all gauges. Errors are logged and the cycle is skipped.
     */
    @Scheduled(every = "${firebird.statistics.interval:1h}",
               identity = "firebird-statistics-collect",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void collectStatistics() {
        if (!statisticsEnabled) {
            return;
        }
        if (!fetcher.isConfigured()) {
            return;
        }
        try {
            processStatisticsOutput(fetcher.fetchStatistics());
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
        if (!fetcher.isConfigured()) {
            return;
        }
        List<ParsedMetric> metrics = FbStatisticsParser.parse(fetcher.database(), data);
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
        if (!fetcher.isConfigured()) {
            return;
        }
        String key = fetcher.database() + "_data_dbfile_pages";
        String db  = fetcher.database();
        values.put(key, pages);
        if (registeredKeys.add(key)) {
            Gauge.builder("data_dbfile_pages",
                    () -> {
                        Number n = values.get(key);
                        return n != null ? n.doubleValue() : Double.NaN;
                    })
                .tag("dbms", "firebirdsql")
                .tag("database", db)
                .register(meterRegistry);
        }
    }

    private void registerGauge(ParsedMetric metric) {
        String key = metric.valueKey();
        if (!registeredKeys.add(key)) {
            return; // gauge already registered; values map update is sufficient
        }
        Gauge.Builder<?> builder = Gauge.builder(metric.metricName(),
            () -> {
                Number n = values.get(key);
                return n != null ? n.doubleValue() : Double.NaN;
            });
        metric.tags().forEach((k, v) -> builder.tag(k, v));
        builder.register(meterRegistry);
    }

    private void fetchAndUpdateDbFilePages() {
        if (!fetcher.isConfigured()) {
            return;
        }
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
}
